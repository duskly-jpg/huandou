package com.huandou.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.net.ssl.SSLSocketFactory;

/**
 * 标准 javax.net.ssl.SSLSocketFactory，产出 SpoofSSLSocket。
 *
 * 直接用于：
 *   - HttpsURLConnection.setSSLSocketFactory(this)  （本工程 MediaDownloader 走这条）
 *   - OkHttp.Builder.sslSocketFactory(this, trustManager) （若以后切 OkHttp）
 *
 * 这样即可在「应用层」替换 TLS ClientHello / 密码套件 / 扩展顺序 / ALPN，
 * 不需要本地 MITM 代理，也不需要 VPNService。
 */
public final class SpoofSSLSocketFactory extends SSLSocketFactory {

    private static volatile SpoofSSLSocketFactory instance;

    private final TlsSpoofConfig cfg;

    public SpoofSSLSocketFactory(TlsSpoofConfig cfg) {
        this.cfg = cfg;
    }

    /** 单例：绑定全局 TlsSpoofConfig。 */
    public static SpoofSSLSocketFactory getInstance() {
        SpoofSSLSocketFactory f = instance;
        if (f == null) {
            synchronized (SpoofSSLSocketFactory.class) {
                f = instance;
                if (f == null) instance = f = new SpoofSSLSocketFactory(TlsSpoofConfig.get());
            }
        }
        return f;
    }

    // 关键重载：包住「已连接」的底层 socket（HttpsURLConnection / OkHttp 都走这条）
    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return new SpoofSSLSocket(s, host, port, autoClose, cfg);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
        Socket base = new Socket(host, port);
        return new SpoofSSLSocket(base, host, port, true, cfg);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException, UnknownHostException {
        Socket base = new Socket(host, port, localHost, localPort);
        return new SpoofSSLSocket(base, host, port, true, cfg);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket base = new Socket(host, port);
        return new SpoofSSLSocket(base, host.getHostAddress(), port, true, cfg);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        Socket base = new Socket(address, port, localAddress, localPort);
        return new SpoofSSLSocket(base, address.getHostAddress(), port, true, cfg);
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return SpoofTlsClient.reportedCipherNames();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return SpoofTlsClient.reportedCipherNames();
    }
}
