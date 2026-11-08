package com.huandou.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * 把「手搓 TLS 1.2」包装成标准 javax.net.ssl.SSLSocket。
 *
 * 这样它就能直接塞进 HttpsURLConnection / OkHttp 的 SSLSocketFactory，
 * 在应用层替换 ClientHello / 密码套件 / 扩展顺序 / ALPN，无需 MITM、无需 VPNService。
 *
 * 用法（由 SpoofSSLSocketFactory 调用）：
 *   SpoofTlsClient.handshake(base, host, port, cfg) 在 startHandshake() 时执行，
 *   握手完成后 getInputStream()/getOutputStream() 透明地加解密应用数据。
 */
final class SpoofSSLSocket extends SSLSocket {

    private final Socket base;          // 已连接的底层 TCP socket
    private final String peerHost;
    private final int peerPort;
    private final boolean autoClose;
    private final TlsSpoofConfig cfg;

    private volatile SpoofTlsClient.Result hs;
    private volatile boolean handshaking = false;
    private boolean useClientMode = true;
    private boolean needClientAuth = false;
    private boolean wantClientAuth = false;
    private boolean enableSessionCreation = true;
    private String[] enabledCipherSuites = SpoofTlsClient.reportedCipherNames();
    private String[] enabledProtocols = {"TLSv1.2"};
    private SSLParameters params = new SSLParameters();

    SpoofSSLSocket(Socket base, String host, int port, boolean autoClose, TlsSpoofConfig cfg) {
        this.base = base;
        this.peerHost = host;
        this.peerPort = port;
        this.autoClose = autoClose;
        this.cfg = cfg;
    }

    // ---------------- 握手 ----------------
    @Override
    public synchronized void startHandshake() throws IOException {
        if (hs != null || handshaking) return;
        handshaking = true;
        try {
            hs = SpoofTlsClient.handshake(base, peerHost, peerPort, cfg);
        } finally {
            handshaking = false;
        }
    }

    private SpoofTlsClient.Result ensure() throws IOException {
        if (hs == null) startHandshake();
        return hs;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return ensure().appIn;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return ensure().appOut;
    }

    @Override
    public SSLSession getSession() {
        try {
            return new BasicSSLSession(ensure(), peerHost, peerPort);
        } catch (IOException e) {
            // 握手尚未完成 / 失败：返回空会话，调用方（HttpsURLConnection）会抛更明确的错
            return new BasicSSLSession(null, peerHost, peerPort);
        }
    }

    @Override
    public SSLSession getHandshakeSession() {
        return getSession();
    }

    // ---------------- SSLSocket 抽象方法 ----------------
    @Override public void setEnableSessionCreation(boolean flag) { enableSessionCreation = flag; }
    @Override public boolean getEnableSessionCreation() { return enableSessionCreation; }
    @Override public void setNeedClientAuth(boolean need) { needClientAuth = need; }
    @Override public boolean getNeedClientAuth() { return needClientAuth; }
    @Override public void setWantClientAuth(boolean want) { wantClientAuth = want; }
    @Override public boolean getWantClientAuth() { return wantClientAuth; }
    @Override public void setUseClientMode(boolean mode) { useClientMode = mode; }
    @Override public boolean getUseClientMode() { return useClientMode; }
    @Override public void setEnabledCipherSuites(String[] suites) { enabledCipherSuites = suites; }
    @Override public String[] getEnabledCipherSuites() { return enabledCipherSuites; }
    @Override public void setEnabledProtocols(String[] protocols) { enabledProtocols = protocols; }
    @Override public String[] getEnabledProtocols() { return enabledProtocols; }
    @Override public String[] getSupportedProtocols() { return new String[]{"TLSv1.2"}; }
    @Override public String[] getSupportedCipherSuites() { return SpoofTlsClient.reportedCipherNames(); }
    @Override public void addHandshakeCompletedListener(javax.net.ssl.HandshakeCompletedListener l) {}
    @Override public void removeHandshakeCompletedListener(javax.net.ssl.HandshakeCompletedListener l) {}

    @Override public SSLParameters getSSLParameters() { return params; }
    @Override public void setSSLParameters(SSLParameters p) { if (p != null) params = p; }

    // ---------------- Socket 状态 / IO 委托给 base ----------------
    @Override public void close() throws IOException {
        if (autoClose || base != null) base.close();
    }
    @Override public boolean isClosed() { return base == null || base.isClosed(); }
    @Override public boolean isConnected() { return base != null && base.isConnected(); }
    @Override public boolean isBound() { return base != null && base.isBound(); }
    @Override public boolean isInputShutdown() { return base != null && base.isInputShutdown(); }
    @Override public boolean isOutputShutdown() { return base != null && base.isOutputShutdown(); }
    @Override public InetAddress getInetAddress() { return base == null ? null : base.getInetAddress(); }
    @Override public InetAddress getLocalAddress() { return base == null ? null : base.getLocalAddress(); }
    @Override public int getPort() { return base == null ? 0 : base.getPort(); }
    @Override public int getLocalPort() { return base == null ? 0 : base.getLocalPort(); }
    @Override public SocketAddress getRemoteSocketAddress() { return base == null ? null : base.getRemoteSocketAddress(); }
    @Override public SocketAddress getLocalSocketAddress() { return base == null ? null : base.getLocalSocketAddress(); }
    @Override public int getReceiveBufferSize() throws SocketException { return base == null ? 0 : base.getReceiveBufferSize(); }
    @Override public void setReceiveBufferSize(int n) throws SocketException { if (base != null) base.setReceiveBufferSize(n); }
    @Override public int getSendBufferSize() throws SocketException { return base == null ? 0 : base.getSendBufferSize(); }
    @Override public void setSendBufferSize(int n) throws SocketException { if (base != null) base.setSendBufferSize(n); }
    @Override public int getSoLinger() throws SocketException { return base == null ? -1 : base.getSoLinger(); }
    @Override public void setSoLinger(boolean on, int v) throws SocketException { if (base != null) base.setSoLinger(on, v); }
    @Override public int getSoTimeout() throws SocketException { return base == null ? 0 : base.getSoTimeout(); }
    @Override public void setSoTimeout(int t) throws SocketException { if (base != null) base.setSoTimeout(t); }
    @Override public int getTrafficClass() throws SocketException { return base == null ? 0 : base.getTrafficClass(); }
    @Override public void setTrafficClass(int tc) throws SocketException { if (base != null) base.setTrafficClass(tc); }
    @Override public boolean getTcpNoDelay() throws SocketException { return base != null && base.getTcpNoDelay(); }
    @Override public void setTcpNoDelay(boolean on) throws SocketException { if (base != null) base.setTcpNoDelay(on); }
    @Override public boolean getKeepAlive() throws SocketException { return base != null && base.getKeepAlive(); }
    @Override public void setKeepAlive(boolean on) throws SocketException { if (base != null) base.setKeepAlive(on); }
    @Override public boolean getReuseAddress() throws SocketException { return base != null && base.getReuseAddress(); }
    @Override public void setReuseAddress(boolean on) throws SocketException { if (base != null) base.setReuseAddress(on); }
    @Override public void shutdownInput() throws IOException { if (base != null) base.shutdownInput(); }
    @Override public void shutdownOutput() throws IOException { if (base != null) base.shutdownOutput(); }
    @Override public void connect(SocketAddress ep) throws IOException { if (base != null) base.connect(ep); }
    @Override public void connect(SocketAddress ep, int t) throws IOException { if (base != null) base.connect(ep, t); }
    @Override public void bind(SocketAddress ep) throws IOException { if (base != null) base.bind(ep); }
    @Override public java.nio.channels.SocketChannel getChannel() { return base == null ? null : base.getChannel(); }
    @Override public String toString() {
        return "SpoofSSLSocket[" + peerHost + ":" + peerPort + ", hs=" + (hs != null) + "]";
    }

    // ---------------- 极简 SSLSession 实现 ----------------
    static final class BasicSSLSession implements SSLSession {
        private final SpoofTlsClient.Result r;
        private final String host; private final int port;
        private final long created = System.currentTimeMillis();
        private final Map<String, Object> values = new HashMap<>();
        private final byte[] id = new byte[32];

        BasicSSLSession(SpoofTlsClient.Result r, String host, int port) {
            this.r = r; this.host = host; this.port = port;
            new java.security.SecureRandom().nextBytes(id);
        }

        @Override public byte[] getId() { return id.clone(); }
        @Override public javax.net.ssl.SSLSessionContext getSessionContext() { return null; }
        @Override public long getCreationTime() { return created; }
        @Override public long getLastAccessedTime() { return created; }
        @Override public void invalidate() {}
        @Override public boolean isValid() { return r != null; }
        @Override public void putValue(String k, Object v) { values.put(k, v); }
        @Override public Object getValue(String k) { return values.get(k); }
        @Override public void removeValue(String k) { values.remove(k); }
        @Override public String[] getValueNames() { return values.keySet().toArray(new String[0]); }
        @Override public Certificate[] getPeerCertificates() {
            return r != null && r.peerChain != null ? r.peerChain.clone() : new Certificate[0];
        }
        @Override @SuppressWarnings("deprecation")
        public javax.security.cert.X509Certificate[] getPeerCertificateChain() {
            if (r == null || r.peerChain == null) return new javax.security.cert.X509Certificate[0];
            javax.security.cert.X509Certificate[] out = new javax.security.cert.X509Certificate[r.peerChain.length];
            try {
                for (int i = 0; i < r.peerChain.length; i++)
                    out[i] = javax.security.cert.X509Certificate.getInstance(r.peerChain[i].getEncoded());
            } catch (Exception e) { return new javax.security.cert.X509Certificate[0]; }
            return out;
        }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Principal getPeerPrincipal() {
            return r != null && r.peerChain != null && r.peerChain.length > 0
                    ? r.peerChain[0].getSubjectX500Principal() : null;
        }
        @Override public Principal getLocalPrincipal() { return null; }
        @Override public String getCipherSuite() { return r != null ? r.cipherSuite : "SSL_NULL_WITH_NULL_NULL"; }
        @Override public String getProtocol() { return r != null ? r.protocol : "NONE"; }
        @Override public String getPeerHost() { return host; }
        @Override public int getPeerPort() { return port; }
        @Override public int getPacketBufferSize() { return 16384; }
        @Override public int getApplicationBufferSize() { return 16384; }
    }
}
