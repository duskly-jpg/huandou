package com.huandou.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.AlgorithmParameters;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 纯 Java 手搓的 TLS 1.2 ECDHE 客户端，ClientHello 字节级可控（模仿 Chrome）。
 *
 * 目的：让「服务器侧看到的 ClientHello」与我们伪装的 UA / Chrome 版本一致，
 * 从而消除「纸面身份 vs 真实 TLS 栈」的矛盾。
 *
 * 用法（两种）：
 *  1) SpoofTlsClient.connect(host, port, cfg) —— 自建 TCP 并握手（测试 / 兼容）。
 *  2) SpoofTlsClient.handshake(connectedSocket, host, port, cfg) —— 复用已连接
 *     socket 完成握手，返回 Result。SpoofSSLSocket 走这条路径，从而可作为
 *     SSLSocketFactory 插入 HttpsURLConnection / OkHttp，无需 MITM / VPNService。
 *
 * 已知边界（与 1.5.4 说明一致）：
 *  - 仅实现 TLS 1.2（ClientHello 不发送 supported_versions，服务器按 1.2 协商）。
 *    仅支持 1.3 的站点会握手失败，上层会回退到系统 TLS，不会断流。
 *  - 支持 ECDHE(X25519 / P-256) + AES-128/256-GCM + ChaCha20-Poly1305，
 *    以及 RSA 密钥交换 + AES-GCM。ClientHello 只暴露这些 AEAD 套件
 *    （CBC/3DES 现代服务器基本不用，且本实现不做）。
 *  - 服务器证书链会用系统信任库严格校验（非 MITM 漏洞），并做主机名校验。
 */
public final class SpoofTlsClient {

    /**
     * 仅 AEAD 的密码套件（数值），顺序即优先级（GREASE 除外）。
     *
     * 注：本实现是纯 TLS 1.2 客户端，不暴露 TLS 1.3 套件（0x1301-0x1303）。
     * 把 1.3 套件塞进 1.2 的 ClientHello 是反常行为（Chrome 只在同时发
     * supported_versions 时才带它们），故剔除，使 JA3 更干净。
     * 暂不暴露 ChaCha20-Poly1305 的原因同上（Android API 24-27 缺参数类）。
     */
    static final int[] CIPHER_SUITES = {
            0xC02B, 0xC02F, 0xC02C, 0xC030,    // ECDHE ECDSA/RSA AES-GCM
            0x009C, 0x009D                     // RSA AES-GCM
    };

    /** 数值 → JSSE 名称，供 SSLSession.getCipherSuite() 使用。 */
    private static final Map<Integer, String> CIPHER_NAMES = new HashMap<>();
    static {
        CIPHER_NAMES.put(0xC02B, "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
        CIPHER_NAMES.put(0xC02F, "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        CIPHER_NAMES.put(0xC02C, "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384");
        CIPHER_NAMES.put(0xC030, "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
        CIPHER_NAMES.put(0xCCA9, "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256");
        CIPHER_NAMES.put(0xCCA8, "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256");
        CIPHER_NAMES.put(0x009C, "TLS_RSA_WITH_AES_128_GCM_SHA256");
        CIPHER_NAMES.put(0x009D, "TLS_RSA_WITH_AES_256_GCM_SHA384");
        CIPHER_NAMES.put(0x002F, "TLS_RSA_WITH_AES_128_CBC_SHA");
        CIPHER_NAMES.put(0x0035, "TLS_RSA_WITH_AES_256_CBC_SHA");
    }

    static String cipherName(int code) {
        String n = CIPHER_NAMES.get(code);
        return n != null ? n : ("0x" + Integer.toHexString(code));
    }

    /** 供 SSLSocketFactory 上报的套件名列表（含 GREASE 占位，仅用于上报）。 */
    static String[] reportedCipherNames() {
        String[] out = new String[CIPHER_SUITES.length];
        for (int i = 0; i < CIPHER_SUITES.length; i++) out[i] = cipherName(CIPHER_SUITES[i]);
        return out;
    }

    /** 与服务器建立伪装 TLS 连接，返回明文应用数据流的封装。 */
    public static Connection connect(String host, int port, TlsSpoofConfig cfg) throws IOException {
        Socket s = new Socket(host, port);
        try {
            return new Connection(handshake(s, host, port, cfg));
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    /** 在「已连接」的 socket 上完成握手，返回握手结果（供 SSLSocket 复用）。 */
    public static Result handshake(Socket base, String host, int port, TlsSpoofConfig cfg) throws IOException {
        return new Handshaker(base, host, port, cfg).run();
    }

    /** 明文应用数据流封装（connect() 的返回值）。 */
    public static final class Connection {
        public final Socket socket;
        public final InputStream in;
        public final OutputStream out;
        Connection(Result r) {
            this.socket = r.base; this.in = r.appIn; this.out = r.appOut;
        }
        public void close() throws IOException { socket.close(); }
    }

    /** 握手结果（handshake() 的返回值）。 */
    public static final class Result {
        final Socket base;
        final InputStream appIn;
        final OutputStream appOut;
        final X509Certificate[] peerChain;
        final String cipherSuite;
        final String protocol;
        final String alpn;
        final String peerHost;
        final int peerPort;
        Result(Socket base, InputStream in, OutputStream out, X509Certificate[] chain,
               int chosenCipher, String alpn, String host, int port) {
            this.base = base; this.appIn = in; this.appOut = out;
            this.peerChain = chain; this.cipherSuite = cipherName(chosenCipher);
            this.protocol = "TLSv1.2"; this.alpn = alpn; this.peerHost = host; this.peerPort = port;
        }
    }

    // ---- 握手状态 ----
    private static final class Handshaker {
        final Socket base; final String host; final int port; final TlsSpoofConfig cfg;
        final SecureRandom rng = new SecureRandom();
        InputStream rawIn; OutputStream rawOut;
        final ByteArrayOutputStream transcript = new ByteArrayOutputStream();

        byte[] clientRandom = new byte[32];
        byte[] serverRandom = new byte[32];
        byte[] masterSecret;

        // 对称密钥（客户端→服务器 / 服务器→客户端 各一套）
        SecretKey cKey, sKey; byte[] cIv, sIv;
        long cSeq, sSeq;

        // 客户端密钥对（两种曲线都预生成，按服务器选择取用）
        byte[] x25519Pub, x25519Priv;
        byte[] p256Pub, p256Priv;

        int chosenCipher;
        X509TrustManager tm;
        int serverNamedCurve;
        String selectedAlpn;
        boolean emsNegotiated = false;   // 服务器是否回显 extended_master_secret（RFC 7627）

        // 握手消息累积缓冲（用于跨记录重组）
        final ByteArrayOutputStream hsRaw = new ByteArrayOutputStream();
        int hsPos = 0;
        final LinkedList<HandshakeMsg> hsQueue = new LinkedList<>();

        Handshaker(Socket b, String h, int p, TlsSpoofConfig c) {
            base = b; host = h; port = p; cfg = c;
        }

        Result run() throws IOException {
            try {
                rawIn = base.getInputStream();
                rawOut = base.getOutputStream();

                // 预生成客户端密钥对
                genClientKeys();

                // 1) 发 ClientHello
                byte[] ch = buildClientHello();
                if (System.getProperty("spoof.debug") != null) {
                    StringBuilder sb = new StringBuilder("CLIENTHELLO_HEX=");
                    for (byte x : ch) sb.append(String.format("%02x", x & 0xFF));
                    System.err.println(sb);
                }
                writeRecord(22, ch, false);

                // 2) 读 ServerHello（确定版本/密码套件/随机，并捕获 ALPN）
                HandshakeMsg sh = readHandshake();
                if (sh.type != 2) throw new IOException("期望 ServerHello，得到类型 " + sh.type);
                parseServerHello(sh.body);

                if (chosenCipher == 0x1301 || chosenCipher == 0x1302 || chosenCipher == 0x1303) {
                    // 服务器选了 TLS 1.3 套件——本实现只做 1.2，交由上层回退。
                    throw new IOException("服务器协商到 TLS1.3 套件，交由系统 TLS 回退");
                }

                // 3) 读后续握手消息直到 ServerHelloDone
                byte[] serverCerts = null;
                byte[] serverEcdhe = null;
                while (true) {
                    HandshakeMsg m = readHandshake();
                    if (m.type == 11) serverCerts = m.body;            // Certificate
                    else if (m.type == 12) serverEcdhe = m.body;       // ServerKeyExchange
                    else if (m.type == 14) break;                       // ServerHelloDone
                }
                if (serverCerts == null) throw new IOException("缺少服务器证书");

                // 4) 校验服务器证书链（沿用系统信任库）
                X509Certificate[] chain = parseCerts(serverCerts);
                verifyChain(chain);

                // 5) 密钥交换（先不派生主密钥）
                byte[] preMaster;
                if (isEcdhe(chosenCipher)) {
                    preMaster = doEcdhe(serverEcdhe);
                } else {
                    preMaster = doRsa(chain[0].getPublicKey());
                }

                // 6) ClientKeyExchange + CCS + Finished
                byte[] cke = buildClientKeyExchange();
                writeRecord(22, cke, false);          // ClientKeyExchange 在 CCS 之前，明文；
                                                      // 此刻 transcript 已含 CKE，可用于 EMS session_hash
                // 主密钥派生必须放在 CKE 写入之后：EMS 模式下 master_secret 依赖含 CKE 的 session_hash。
                deriveKeys(preMaster);
                writeChangeCipherSpec();
                byte[] clientFinished = computeFinished("client finished");
                writeRecord(22, clientFinished, true);  // Finished 已切换加密

                // 7) 读服务器的 CCS + Finished。服务器可能在「初始航班」里、自身 CCS
                //    （加密生效）之前就发来明文 NewSessionTicket（type 22）；也可能在 CCS
                //    之后发来加密的 Finished / NewSessionTicket。稳健处理：
                //   - 未见 CCS 前：type-22 为明文，直接解析并跳过（NewSessionTicket）；
                //   - 见 CCS 后：type-22 为加密，解密后找 Finished（type 0x14）。
                boolean gotCcs = false;
                HandshakeMsg sf = null;
                while (sf == null) {
                    Record r = readAnyRecord(gotCcs);     // 仅已过 CCS 才解密 type-22
                    if (r.type == 20) {                 // ChangeCipherSpec（明文）
                        gotCcs = true;
                    } else if (r.type == 22) {          // 握手记录（明文或加密，由 gotCcs 决定）
                        hsRaw.write(r.data, 0, r.data.length);
                        parseHandshake();
                        while (!hsQueue.isEmpty()) {
                            HandshakeMsg m = hsQueue.poll();
                            if (m.type == 0x14) sf = m;            // Finished
                            else if (m.type == 0x04) {             // NewSessionTicket：跳过
                                if (System.getProperty("spoof.debug") != null)
                                    System.err.println("DEBUG skip NewSessionTicket len=" + m.body.length);
                            }
                            // 其它握手消息忽略
                        }
                    } else if (r.type == 21) {          // alert
                        throw new IOException("服务器在客户端 Finished 后发送警报，连接关闭");
                    } else {
                        throw new IOException("握手后收到意外记录类型 " + r.type);
                    }
                }
                if (!gotCcs) throw new IOException("服务器未发送 ChangeCipherSpec");
                verifyFinished(sf.body, "server finished");

                // 8) 返回结果
                return new Result(base, new AppIn(), new AppOut(), chain,
                        chosenCipher, selectedAlpn, host, port);
            } catch (IOException e) {
                if (base != null) try { base.close(); } catch (IOException ignored) {}
                throw e;
            } catch (Exception e) {
                if (base != null) try { base.close(); } catch (IOException ignored) {}
                throw new IOException("TLS 握手失败: " + e, e);
            }
        }

        // ---------------- ClientHello ----------------
        private byte[] buildClientHello() {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            // client_version 0x0303
            b.write(0x03); b.write(0x03);
            // random: 前 4 字节为 Unix 时间，后 28 随机
            long t = System.currentTimeMillis() / 1000;
            clientRandom[0] = (byte) (t >>> 24); clientRandom[1] = (byte) (t >>> 16);
            clientRandom[2] = (byte) (t >>> 8); clientRandom[3] = (byte) (t);
            byte[] rnd = new byte[28]; rng.nextBytes(rnd);
            System.arraycopy(rnd, 0, clientRandom, 4, 28);
            writeBytes(b, clientRandom);
            // session_id 空
            b.write(0);
            // cipher suites（含 GREASE）
            ByteArrayOutputStream cs = new ByteArrayOutputStream();
            putU16(cs, greaseCipher());
            for (int c : CIPHER_SUITES) putU16(cs, c);
            putU16(b, cs.size()); writeBytes(b, cs.toByteArray());
            // compression: null
            b.write(1); b.write(0);
            // extensions：顺序严格对齐 Chrome 的 1.2 ClientHello 前缀
            //  （实拍 Chrome：0-23-65281-10-11-35-16-5-13-18-...）。
            //  JA3/JA4 都按出现顺序取值，因此顺序本身就是指纹的一部分，不能随意排。
            ByteArrayOutputStream ext = new ByteArrayOutputStream();
            addExt(ext, grease(), new byte[0]);                                   // GREASE 扩展
            addExt(ext, 0x0000, buildSni());                                      // server_name
            addExt(ext, 0x0017, new byte[0]);                                     // extended_master_secret（RFC 7627）
            addExt(ext, 0xFF01, new byte[]{0x00});                                // renegotiation_info（Chrome 放在第 3 位）
            addExt(ext, 0x000A, buildSupportedGroups());                          // supported_groups
            addExt(ext, 0x000B, new byte[]{0x01, 0x00});                          // ec_point_formats
            addExt(ext, 0x0023, new byte[0]);                                     // session_ticket
            addExt(ext, 0x0010, buildAlpn());                                     // ALPN
            addExt(ext, 0x0005, new byte[]{0x01, 0x00, 0x00, 0x00, 0x00});        // status_request（OCSP）
            addExt(ext, 0x000D, buildSigAlgs());                                  // signature_algorithms
            addExt(ext, 0x0012, new byte[0]);                                     // signed_certificate_timestamp（Chrome 必发，空体）
            putU16(b, ext.size()); writeBytes(b, ext.toByteArray());

            byte[] body = b.toByteArray();
            ByteArrayOutputStream hh = new ByteArrayOutputStream();
            hh.write(1); putU24(hh, body.length); writeBytes(hh, body);
            byte[] msg = hh.toByteArray();
            transcript.write(msg, 0, msg.length);
            return msg;   // 返回带 4 字节握手头的完整消息
        }

        private byte[] buildSni() {
            byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            inner.write(0);                                  // name_type: host_name（1 字节）
            putU16(inner, name.length); writeBytes(inner, name);
            putU16(o, inner.size()); writeBytes(o, inner.toByteArray());
            return o.toByteArray();
        }

        private byte[] buildSupportedGroups() {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            putU16(o, 8);                 // 4 groups * 2
            putU16(o, grease());          // GREASE
            putU16(o, 0x001D);            // x25519
            putU16(o, 0x0017);            // secp256r1
            putU16(o, 0x0018);            // secp384r1
            return o.toByteArray();
        }

        private byte[] buildSigAlgs() {
            int[] algs = {0x0403,0x0503,0x0603,0x0804,0x0805,0x0806,0x0401,0x0501,0x0601,0x0201};
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            putU16(o, algs.length * 2);
            for (int a : algs) putU16(o, a);
            return o.toByteArray();
        }

        private byte[] buildAlpn() {
            String[] protos = cfg.getAlpn();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            ByteArrayOutputStream list = new ByteArrayOutputStream();
            for (String p : protos) {
                byte[] pb = p.getBytes(StandardCharsets.US_ASCII);
                list.write(pb.length); writeBytes(list, pb);
            }
            putU16(o, list.size()); writeBytes(o, list.toByteArray());
            return o.toByteArray();
        }

        private int grease() { return cfg.getGrease(); }
        private int greaseCipher() { return cfg.getGrease(); }

        private void addExt(ByteArrayOutputStream ext, int type, byte[] data) {
            putU16(ext, type); putU16(ext, data.length); writeBytes(ext, data);
        }

        // ---------------- ServerHello 解析 ----------------
        private void parseServerHello(byte[] b) throws IOException {
            int p = 0;
            int ver = ((b[p]&0xFF)<<8)|(b[p+1]&0xFF); p += 2;
            System.arraycopy(b, p, serverRandom, 0, 32); p += 32;
            int sidLen = b[p++]&0xFF; p += sidLen;
            chosenCipher = ((b[p]&0xFF)<<8)|(b[p+1]&0xFF); p += 2;
            p += 1; // compression
            if (ver != 0x0303) throw new IOException("服务器未协商 TLS 1.2 (ver=0x"
                    + Integer.toHexString(ver) + ")");
            // 解析扩展，捕获 ALPN 选择
            if (p + 2 <= b.length) {
                int extLen = ((b[p]&0xFF)<<8)|(b[p+1]&0xFF); p += 2;
                int end = p + extLen;
                while (p + 4 <= end) {
                    int type = ((b[p]&0xFF)<<8)|(b[p+1]&0xFF); p += 2;
                    int dlen = ((b[p]&0xFF)<<8)|(b[p+1]&0xFF); p += 2;
                    if (type == 0x0010 && p + dlen <= end) {      // ALPN
                        int q = p, dEnd = p + dlen;
                        int listLen = ((b[q]&0xFF)<<8)|(b[q+1]&0xFF); q += 2;
                        if (listLen == dlen - 2) {
                            int plen = b[q++] & 0xFF;
                            selectedAlpn = new String(Arrays.copyOfRange(b, q, q + plen),
                                    StandardCharsets.US_ASCII);
                        }
                    } else if (type == 0x0017) {                  // extended_master_secret 被回显 → 启用 EMS
                        emsNegotiated = true;
                    }
                    p += dlen;
                }
            }
        }

        // ---------------- 密钥交换 ----------------
        private boolean isEcdhe(int c) {
            return c==0xC02B||c==0xC02F||c==0xC02C||c==0xC030||c==0xCCA9||c==0xCCA8;
        }
        private boolean isChacha(int c) { return c==0xCCA9||c==0xCCA8; }
        // AES-256-GCM 套件需 32 字节密钥（其余 AEAD 为 16 字节）。
        private static boolean isAes256(int c) {
            return c==0x009D || c==0xC02C || c==0xC030;
        }

        private byte[] doEcdhe(byte[] ske) throws Exception {
            int p = 0;
            int curveType = ske[p++]&0xFF;
            if (curveType != 3) throw new IOException("不支持的曲线类型");
            int named = ((ske[p]&0xFF)<<8)|(ske[p+1]&0xFF); p += 2;
            int plen = ske[p++]&0xFF;
            byte[] serverPub = Arrays.copyOfRange(ske, p, p + plen); p += plen;
            // 跳过签名（按设计不校验 SKE 签名）

            if (named == 0x001D) { // x25519
                serverNamedCurve = named;
                return X25519.shared(x25519Priv, serverPub);
            } else if (named == 0x0017) { // secp256r1
                serverNamedCurve = named;
                KeyFactory kf = KeyFactory.getInstance("EC");
                PublicKey sp = kf.generatePublic(new X509EncodedKeySpec(
                        ecPointToSubjectPublicKeyInfo(serverPub)));
                KeyAgreement ka = KeyAgreement.getInstance("ECDH");
                ka.init(kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(
                        p256Priv)));
                ka.doPhase(sp, true);
                return ka.generateSecret();   // JDK9+/Android: 无参版本返回原始共享密钥字节（P-256 为 32 字节 x 坐标）
            } else {
                throw new IOException("不支持的命名曲线 0x" + Integer.toHexString(named));
            }
        }

        private byte[] doRsa(PublicKey serverRsaPub) throws Exception {
            byte[] pm = new byte[48];
            pm[0] = 0x03; pm[1] = 0x03;
            byte[] rnd = new byte[46]; rng.nextBytes(rnd);
            System.arraycopy(rnd, 0, pm, 2, 46);
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, serverRsaPub);
            return c.doFinal(pm);
        }

        // ---------------- 密钥派生（TLS 1.2 PRF） ----------------
        // 主密钥：默认 PRF(pms, "master secret", clientRandom||serverRandom)。
        // 若服务器回显了 extended_master_secret（RFC 7627），则改用
        //   PRF(pms, "extended master secret", session_hash)，其中
        //   session_hash = Hash(全部握手消息，直至并含 ClientKeyExchange)。
        private void deriveKeys(byte[] preMaster) throws Exception {
            // 先按协商套件确定密钥长度与 PRF 哈希（AES-128-GCM→SHA-256，AES-256-GCM-SHA384→SHA-384）
            boolean aes256 = isAes256(chosenCipher);
            int keyLen = aes256 ? 32 : 16;
            prfHash = aes256 ? "HmacSHA384" : "HmacSHA256";
            prfDigest = aes256 ? "SHA-384" : "SHA-256";

            if (System.getProperty("spoof.debug") != null)
                System.err.println("DEBUG emsNegotiated=" + emsNegotiated + " transcriptLen=" + transcript.size());
            if (emsNegotiated) {
                MessageDigest md = MessageDigest.getInstance(prfDigest);
                byte[] sessionHash = md.digest(transcript.toByteArray());
                masterSecret = prf(preMaster, "extended master secret", sessionHash, 48);
            } else {
                masterSecret = prf(preMaster, "master secret",
                        concat(clientRandom, serverRandom), 48);
            }

            // 密钥块：写密钥 + 写 IV(4) + 读密钥 + 读 IV(4)
            int ivLen = 4;                                       // GCM 的 write-IV
            int total = 2 * keyLen + 2 * ivLen;
            byte[] keyBlock = prf(masterSecret, "key expansion", concat(serverRandom, clientRandom), total);

            int off = 0;
            byte[] ck = Arrays.copyOfRange(keyBlock, off, off+keyLen); off += keyLen;
            byte[] sk = Arrays.copyOfRange(keyBlock, off, off+keyLen); off += keyLen;
            byte[] civ = Arrays.copyOfRange(keyBlock, off, off+ivLen); off += ivLen;
            byte[] siv = Arrays.copyOfRange(keyBlock, off, off+ivLen); off += ivLen;

            String alg = isChacha(chosenCipher) ? "ChaCha20-Poly1305" : "AES";
            cKey = new SecretKeySpec(ck, alg);
            sKey = new SecretKeySpec(sk, alg);
            cIv = civ; sIv = siv;
            cSeq = 0; sSeq = 0;
            if (System.getProperty("spoof.debug") != null) {
                System.err.println("DEBUG chosenCipher=0x" + Integer.toHexString(chosenCipher)
                        + " curve=0x" + Integer.toHexString(serverNamedCurve));
                System.err.println("DEBUG preMaster(" + preMaster.length + ")=" + hex(preMaster));
                System.err.println("DEBUG master(" + masterSecret.length + ")=" + hex(masterSecret));
                System.err.println("DEBUG cKey=" + hex(ck) + " cIV=" + hex(civ));
                System.err.println("DEBUG sKey=" + hex(sk) + " sIV=" + hex(siv));
                System.err.println("DEBUG clientRandom=" + hex(clientRandom));
                System.err.println("DEBUG serverRandom=" + hex(serverRandom));
            }
        }

        // TLS 1.2 PRF：P_hash = HMAC-(SHA256/SHA384) 的串联（RFC 5246 §5）。
        // 注意：① 必须用 HMAC，不能用裸 SHA；② 哈希算法由协商套件决定
        // （AES-128-GCM 套件用 SHA-256，AES-256-GCM-SHA384 套件用 SHA-384）。
        private String prfHash = "HmacSHA256";
        private String prfDigest = "SHA-256";
        private byte[] prf(byte[] secret, String label, byte[] seed, int outLen) throws Exception {
            byte[] labelSeed = concat(label.getBytes(StandardCharsets.US_ASCII), seed);
            Mac mac = Mac.getInstance(prfHash);
            mac.init(new SecretKeySpec(secret, prfHash));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] a = labelSeed;                 // A(0) = seed（= label + seed）
            while (out.size() < outLen) {
                mac.update(a);
                byte[] aNew = mac.doFinal();      // A(i) = HMAC(secret, A(i-1))
                mac.update(aNew);
                mac.update(labelSeed);
                out.write(mac.doFinal());         // HMAC(secret, A(i) + label + seed)
                a = aNew;
            }
            return Arrays.copyOfRange(out.toByteArray(), 0, outLen);
        }

        // ---------------- Finished ----------------
        private byte[] computeFinished(String label) throws Exception {
            if (System.getProperty("spoof.debug") != null) {
                byte[] t = transcript.toByteArray();
                StringBuilder sb = new StringBuilder("DEBUG transcript msgs: ");
                int i = 0;
                while (i + 4 <= t.length) {
                    int ty = t[i] & 0xFF;
                    int ln = ((t[i+1]&0xFF)<<16)|((t[i+2]&0xFF)<<8)|(t[i+3]&0xFF);
                    sb.append("(").append(ty).append(":").append(ln).append(") ");
                    i += 4 + ln;
                }
                System.err.println(sb + " totalLen=" + t.length);
            }
            MessageDigest md = MessageDigest.getInstance(prfDigest);
            byte[] h = md.digest(transcript.toByteArray());
            // PRF 内部会拼接 label + seed；这里 seed 只传 Hash(handshake_messages)，不可再带 label。
            byte[] v = prf(masterSecret, label, h, 12);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(0x14); putU24(o, v.length); writeBytes(o, v);
            byte[] msg = o.toByteArray();
            transcript.write(msg, 0, msg.length);
            return msg;
        }

        private void verifyFinished(byte[] verifyData, String label) throws Exception {
            // 注意：verifyData 已是「去掉 4 字节握手头」后的 verify_data（12 字节），
            // 直接用于比对，不要再解析长度头。

            if (System.getProperty("spoof.debug") != null) {
                MessageDigest md = MessageDigest.getInstance(prfDigest);
                byte[] h = md.digest(transcript.toByteArray());
                byte[] calc = prf(masterSecret, label, h, 12);
                System.err.println("DEBUG srvFinished.verifyDataLen=" + verifyData.length);
                System.err.println("DEBUG srvFinished.expected =" + hex(verifyData));
                System.err.println("DEBUG srvFinished.calcWith22 =" + hex(calc));
                byte[] without = stripLastType(transcript.toByteArray(), 22);
                byte[] calc2 = prf(masterSecret, label, md.digest(without), 12);
                System.err.println("DEBUG srvFinished.calcWithout22=" + hex(calc2));
            }

            MessageDigest md = MessageDigest.getInstance(prfDigest);
            byte[] h = md.digest(transcript.toByteArray());
            byte[] calc = prf(masterSecret, label, h, 12);
            if (!Arrays.equals(verifyData, calc)) throw new IOException("Finished 校验失败");
        }

        private static byte[] stripLastType(byte[] full, int type) {
            // 从末尾向前找最后一个「type」握手消息并剔除（含 4 字节头）。
            int p = full.length;
            while (p >= 4) {
                int t = full[p-4] & 0xFF;
                int l = ((full[p-3]&0xFF)<<16)|((full[p-2]&0xFF)<<8)|(full[p-1]&0xFF);
                if (t == type) return Arrays.copyOfRange(full, 0, p - 4 - l);
                p -= 4 + l;
            }
            return full;
        }

        // ---------------- ClientKeyExchange ----------------
        private byte[] buildClientKeyExchange() {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            if (serverNamedCurve == 0x0017) {        // 服务器选了 P-256 → 发 P-256 公钥
                o.write(65); writeBytes(o, p256Pub);
            } else {                                  // 默认 x25519
                o.write(32); writeBytes(o, x25519Pub);
            }
            byte[] body = o.toByteArray();
            ByteArrayOutputStream hh = new ByteArrayOutputStream();
            hh.write(0x10); putU24(hh, body.length); writeBytes(hh, body);
            byte[] msg = hh.toByteArray();
            transcript.write(msg, 0, msg.length);
            return msg;
        }

        // ---------------- 证书 ----------------
        private X509Certificate[] parseCerts(byte[] body) throws Exception {
            int p = 0;
            int n = ((body[p]&0xFF)<<16)|((body[p+1]&0xFF)<<8)|(body[p+2]&0xFF); p += 3;
            // 跳过整体长度（已由 body 给出）
            List<X509Certificate> list = new ArrayList<>();
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            while (p < body.length) {
                int cl = ((body[p]&0xFF)<<16)|((body[p+1]&0xFF)<<8)|(body[p+2]&0xFF); p += 3;
                byte[] der = Arrays.copyOfRange(body, p, p + cl); p += cl;
                list.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
            }
            return list.toArray(new X509Certificate[0]);
        }

        private void verifyChain(X509Certificate[] chain) throws Exception {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
            // 默认用系统信任库；测试可经系统属性 spoof.trustStore 追加自定义 CA（生产不设即为默认行为）。
            String ts = System.getProperty("spoof.trustStore");
            if (ts != null) {
                java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
                try (java.io.FileInputStream fis = new java.io.FileInputStream(ts)) {
                    ks.load(fis, System.getProperty("spoof.trustStorePassword", "").toCharArray());
                }
                tmf.init(ks);
            } else {
                tmf.init((java.security.KeyStore) null);
            }
            tm = (X509TrustManager) tmf.getTrustManagers()[0];
            // 依据本次协商的密钥交换 / 叶子证书算法选 authType，并做回退
            String leafAlg = chain[0].getPublicKey().getAlgorithm();
            String[] candidates;
            if (isEcdhe(chosenCipher)) {
                candidates = "EC".equalsIgnoreCase(leafAlg)
                        ? new String[]{"ECDHE_ECDSA", "ECDHE_RSA", "EC", "RSA"}
                        : new String[]{"ECDHE_RSA", "ECDHE_ECDSA", "RSA", "EC"};
            } else {
                candidates = new String[]{"RSA", "ECDHE_RSA"};
            }
            Throwable last = null;
            for (String auth : candidates) {
                try { tm.checkServerTrusted(chain, auth); last = null; break; }
                catch (Throwable e) { last = e; }
            }
            if (last != null) throw new IOException("服务器证书不受信任: " + last, (last instanceof Exception ? (Exception) last : new Exception(last)));
            // 主机名校验
            if (!hostMatches(chain[0])) throw new IOException("主机名不匹配: " + host);
        }

        private boolean hostMatches(X509Certificate leaf) {
            boolean ok = false;
            try {
                java.util.Collection<List<?>> sans = leaf.getSubjectAlternativeNames();
                if (sans != null) for (List<?> alt : sans) {
                    if ((Integer) alt.get(0) == 2) {
                        String v = (String) alt.get(1);
                        if (v.equalsIgnoreCase(host) || matchesWildcard(v, host)) ok = true;
                    }
                }
            } catch (Exception ignored) {}
            if (!ok) {
                String cn = cnOf(leaf);
                if (cn != null && (cn.equalsIgnoreCase(host) || matchesWildcard(cn, host))) ok = true;
            }
            return ok;
        }

        // ---------------- 记录层（加密侧） ----------------
        // encrypt=true 表示用协商出的密钥加密（Finished / 应用数据）；
        // encrypt=false 表示明文握手记录（ClientHello / ClientKeyExchange），版本用 0x0301。
        private void writeRecord(int type, byte[] payload, boolean encrypt) throws IOException {
            // 记录层版本统一用 0x0303（现代客户端/服务器都如此；Chrome 也不例外）。
            int ver = 0x0303;
            int off = 0;
            while (off < payload.length) {
                int frag = Math.min(payload.length - off, 16384);
                byte[] plain = Arrays.copyOfRange(payload, off, off + frag);
                byte[] enc = encrypt ? seal(type, plain) : plain;
                rawOut.write(type);
                rawOut.write((ver>>8)&0xFF); rawOut.write(ver&0xFF);
                rawOut.write((enc.length>>8)&0xFF); rawOut.write(enc.length&0xFF);
                rawOut.write(enc);
                off += frag;
            }
            rawOut.flush();
        }

        private byte[] seal(int type, byte[] plain) throws IOException {
            try {
                byte[] nonce = concat(cIv, u64(cSeq));   // 12 字节 GCM nonce = write_IV(4) || seq(8)
                byte[] aad = buildAad(cSeq, type, plain.length);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, cKey, new GCMParameterSpec(128, nonce));
                c.updateAAD(aad);
                byte[] ct = c.doFinal(plain);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                writeU64(o, cSeq);          // 显式 nonce（GCM 记录前 8 字节）
                o.write(ct);
                cSeq++;
                return o.toByteArray();
            } catch (Exception e) {
                throw new IOException("加密失败: " + e, e);
            }
        }

        private void writeChangeCipherSpec() throws IOException {
            rawOut.write(20); rawOut.write(0x03); rawOut.write(0x03);
            rawOut.write(0); rawOut.write(1); rawOut.write(1);
            rawOut.flush();
        }

        // ---------------- 记录层（解密侧，握手阶段） ----------------
        // encrypted=false：明文握手记录（ClientHello 之后、CCS 之前）。
        // encrypted=true ：已切换加密后的握手记录（服务器 Finished 是加密记录，
        //   必须用协商密钥解密，否则把密文当握手消息解析会误判类型）。
        private HandshakeMsg readHandshake() throws IOException {
            return readHandshake(false);
        }
        private HandshakeMsg readHandshake(boolean encrypted) throws IOException {
            while (true) {
                if (!hsQueue.isEmpty()) return hsQueue.poll();
                Record r = nextRecord(encrypted);
                if (r.type != 22) throw new IOException("握手期收到非握手记录 type=" + r.type);
                hsRaw.write(r.data, 0, r.data.length);
                parseHandshake();
            }
        }

        private void parseHandshake() {
            byte[] a = hsRaw.toByteArray();
            while (hsPos + 4 <= a.length) {
                int type = a[hsPos] & 0xFF;
                int len = ((a[hsPos + 1] & 0xFF) << 16) | ((a[hsPos + 2] & 0xFF) << 8) | (a[hsPos + 3] & 0xFF);
                if (hsPos + 4 + len > a.length) break; // 还不完整
                byte[] body = Arrays.copyOfRange(a, hsPos + 4, hsPos + 4 + len);
                hsPos += 4 + len;
                if (type != 0x14) {
                    ByteArrayOutputStream t = new ByteArrayOutputStream();
                    t.write(type); putU24(t, len); t.write(body, 0, body.length);
                    transcript.write(t.toByteArray(), 0, t.size());
                }
                hsQueue.add(new HandshakeMsg(type, body));
            }
        }

        private Record nextRecord(boolean encrypted) throws IOException {
            int type = rawIn.read();
            if (type < 0) throw new IOException("连接关闭");
            int v1 = rawIn.read(), v2 = rawIn.read();
            int len = (rawIn.read() << 8) | rawIn.read();
            if (System.getProperty("spoof.debug") != null)
                System.err.println("DEBUG nextRecord type=" + type + " ver=" + v1 + "." + v2 + " len=" + len + " encrypted=" + encrypted);
            byte[] buf = new byte[len];
            int got = 0; while (got < len) { int n = rawIn.read(buf, got, len - got); if (n < 0) break; got += n; }
            if (encrypted) {
                byte[] explicit = Arrays.copyOfRange(buf, 0, 8);
                byte[] ct = Arrays.copyOfRange(buf, 8, buf.length);
                byte[] fullNonce = concat(sIv, explicit);
                // AAD 长度 = 明文长度 = 记录长度(含显式 nonce) - 8(显式 nonce) - 16(GCM 标签)
                byte[] aad = buildAad(sSeq, type, len - 24);
                try {
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, sKey, new GCMParameterSpec(128, fullNonce));
                    c.updateAAD(aad);
                    buf = c.doFinal(ct);
                } catch (Exception e) {
                    throw new IOException("解密服务器记录失败: " + e, e);
                }
                sSeq++;
            }
            return new Record(type, buf);
        }

        // 读取下一条记录（客户端 Finished 之后）。
        // 注意：服务器可能在「初始航班」里、尚未发送自身 CCS（即加密尚未生效）时就
        // 发出明文握手记录（如 NewSessionTicket，type 22）。因此解密与否取决于
        // 是否已经过了 CCS：encrypted=true（已过 CCS）时 type-22 才按加密处理。
        private Record readAnyRecord(boolean encrypted) throws IOException {
            int type = rawIn.read();
            if (type < 0) throw new IOException("连接关闭");
            int v1 = rawIn.read(), v2 = rawIn.read();
            int len = (rawIn.read() << 8) | rawIn.read();
            if (System.getProperty("spoof.debug") != null)
                System.err.println("DEBUG readAnyRecord type=" + type + " ver=" + v1 + "." + v2 + " len=" + len + " encrypted=" + encrypted);
            byte[] buf = new byte[len];
            int got = 0; while (got < len) { int n = rawIn.read(buf, got, len - got); if (n < 0) break; got += n; }
            if (encrypted && type == 22) {   // 已过 CCS：握手记录已加密，用服务器密钥解密
                byte[] explicit = Arrays.copyOfRange(buf, 0, 8);
                byte[] ct = Arrays.copyOfRange(buf, 8, buf.length);
                byte[] fullNonce = concat(sIv, explicit);
                byte[] aad = buildAad(sSeq, type, len - 24);
                try {
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, sKey, new GCMParameterSpec(128, fullNonce));
                    c.updateAAD(aad);
                    buf = c.doFinal(ct);
                } catch (Exception e) {
                    throw new IOException("解密服务器记录失败: " + e, e);
                }
                sSeq++;
            }
            return new Record(type, buf);
        }

        // ---------------- 应用数据读写（加密） ----------------
        private final class AppIn extends InputStream {
            byte[] buf = new byte[0]; int pos = 0;
            boolean appEof = false;   // 收到 close_notify 后保持 EOF，避免重复读取抛异常
            public int read() throws IOException {
                if (appEof) return -1;
                while (pos >= buf.length) {
                    Record r = nextRecord(true);
                    if (r.type == 21) { appEof = true; return -1; }   // close_notify：视为 EOF
                    if (r.type == 4) {             // NewSessionTicket：握手后可能出现，跳过
                        if (System.getProperty("spoof.debug") != null)
                            System.err.println("DEBUG appin skip NewSessionTicket len=" + r.data.length);
                        continue;
                    }
                    if (r.type != 23) {
                        if (System.getProperty("spoof.debug") != null)
                            System.err.println("DEBUG appin unexpected record type=" + r.type);
                        throw new IOException("应用期收到非应用记录 " + r.type);
                    }
                    buf = r.data; pos = 0;
                    if (buf.length == 0) continue;   // 空应用记录不表示 EOF，继续等下一条
                }
                return buf[pos++] & 0xFF;
            }

            /**
             * 必须遵守 InputStream 约定：**有数据就立刻返回，不要求填满 b**。
             *
             * HttpsURLConnection / OkHttp 都用 8KB 缓冲读响应体。若这里阻塞到填满，
             * 遇到 keep-alive 且响应尾包不足 8KB 时就会一直等到读超时，
             * 表现为「下载卡住 / Read timed out」。
             */
            public int read(byte[] b, int off, int l) throws IOException {
                if (l == 0) return 0;
                if (pos < buf.length) {                 // 当前记录还有余量：直接给出去
                    int n = Math.min(l, buf.length - pos);
                    System.arraycopy(buf, pos, b, off, n);
                    pos += n;
                    return n;
                }
                int c = read();                          // 缓冲已空：阻塞取一条记录的首字节
                if (c < 0) return -1;
                b[off] = (byte) c;
                int got = 1;
                while (got < l && pos < buf.length) {     // 同一条记录内的剩余字节顺手带上
                    b[off + got++] = buf[pos++];
                }
                return got;
            }
        }

        private final class AppOut extends OutputStream {
            public void write(int b) throws IOException { write(new byte[]{(byte)b}); }
            public void write(byte[] b, int off, int l) throws IOException {
                byte[] frag = Arrays.copyOfRange(b, off, off + l);
                byte[] enc = seal(23, frag);
                rawOut.write(23); rawOut.write(0x03); rawOut.write(0x03);
                rawOut.write((enc.length>>8)&0xFF); rawOut.write(enc.length&0xFF);
                rawOut.write(enc);
                rawOut.flush();
            }
        }

        // ---------------- 小工具 ----------------
        private static final class HandshakeMsg { final int type; final byte[] body; HandshakeMsg(int t, byte[] b){type=t;body=b;} }
        private static final class Record { final int type; final byte[] data; Record(int t, byte[] d){type=t;data=d;} }

        private byte[] buildAad(long seq, int type, int len) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            writeU64(o, seq);
            o.write(type); o.write(0x03); o.write(0x03);
            o.write((len>>8)&0xFF); o.write(len&0xFF);
            return o.toByteArray();
        }
        private static byte[] u64(long v) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            writeU64(o, v);
            return o.toByteArray();
        }

        private void genClientKeys() throws Exception {
            // X25519：纯 Java 实现（不依赖 JCA 的 X25519 / BouncyCastle，minSdk 24 即可用）
            x25519Priv = X25519.generatePrivate(rng);
            x25519Pub = X25519.publicFromPrivate(x25519Priv);
            // P-256：仍用标准 JCA（所有 Android 版本都支持）
            KeyPairGenerator eg = KeyPairGenerator.getInstance("EC");
            eg.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            java.security.KeyPair ep = eg.generateKeyPair();
            p256Priv = ep.getPrivate().getEncoded();
            p256Pub = publicRaw(ep.getPublic());
        }

        private byte[] publicRaw(PublicKey pub) {
            byte[] enc = pub.getEncoded();
            if (enc.length == 44) return Arrays.copyOfRange(enc, 12, 44); // X25519 SPKI
            return Arrays.copyOfRange(enc, enc.length - 65, enc.length);   // EC 裸点
        }
        private byte[] ecPointToSubjectPublicKeyInfo(byte[] point65) throws Exception {
            AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
            java.security.spec.ECParameterSpec spec = ap.getParameterSpec(java.security.spec.ECParameterSpec.class);
            java.security.spec.ECPoint pt = decodePoint(point65);
            java.security.spec.ECPublicKeySpec eps = new java.security.spec.ECPublicKeySpec(pt, spec);
            return KeyFactory.getInstance("EC").generatePublic(eps).getEncoded();
        }
        private java.security.spec.ECPoint decodePoint(byte[] p) {
            int len = (p.length-1)/2;
            byte[] x = Arrays.copyOfRange(p, 1, 1+len);
            byte[] y = Arrays.copyOfRange(p, 1+len, 1+2*len);
            return new java.security.spec.ECPoint(new BigInteger(1, x), new BigInteger(1, y));
        }

        private String cnOf(X509Certificate c) {
            String dn = c.getSubjectX500Principal().getName();
            for (String kv : dn.split(",")) {
                kv = kv.trim();
                if (kv.startsWith("CN=")) return kv.substring(3);
            }
            return null;
        }
        private boolean matchesWildcard(String pattern, String host) {
            if (pattern.startsWith("*.")) {
                String base = pattern.substring(2);
                int dot = host.indexOf('.');
                return dot > 0 && host.substring(dot+1).equalsIgnoreCase(base);
            }
            return false;
        }
    }

    // ---------------- 公共小工具 ----------------
    static void putU16(ByteArrayOutputStream o, int v) { o.write((v>>8)&0xFF); o.write(v&0xFF); }
    static void putU24(ByteArrayOutputStream o, int v) { o.write((v>>16)&0xFF); o.write((v>>8)&0xFF); o.write(v&0xFF); }
    static void writeBytes(ByteArrayOutputStream o, byte[] b) { o.write(b, 0, b.length); }
    static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xFF));
        return s.toString();
    }
    static void writeU64(ByteArrayOutputStream o, long v) {
        for (int i = 7; i >= 0; i--) o.write((int)((v >>> (i*8)) & 0xFF));
    }
    static byte[] concat(byte[]... arrs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] a : arrs) o.write(a, 0, a.length);
        return o.toByteArray();
    }
}
