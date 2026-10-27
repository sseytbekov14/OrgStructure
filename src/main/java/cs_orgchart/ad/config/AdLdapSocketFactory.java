package cs_orgchart.ad.config;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/**
 * TLS sockets for the LDAP connection only (spec v2.1 §8.1): JNDI creates them through
 * {@code java.naming.ldap.factory.socket}, which takes a class name with a static {@link #getDefault()}, so the
 * trust settings are held here. The JVM-wide truststore ({@code javax.net.ssl.*}) stays untouched: the SAML
 * metadata fetch from Entra and every other TLS connection keep the default one.
 */
public final class AdLdapSocketFactory extends SSLSocketFactory {

    private static volatile SSLSocketFactory configured;

    private final SSLSocketFactory delegate;

    private AdLdapSocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    /** The TLS settings of the LDAP connection; one AD connection per application */
    static void use(SSLContext context) {
        configured = context.getSocketFactory();
    }

    /** Called by JNDI for every new LDAP connection */
    public static SocketFactory getDefault() {
        SSLSocketFactory factory = configured;
        if (factory == null) {
            throw new IllegalStateException("AdLdapSocketFactory used before the AD truststore was loaded");
        }
        return new AdLdapSocketFactory(factory);
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        return delegate.createSocket();
    }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        return delegate.createSocket(socket, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return delegate.createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return delegate.createSocket(address, port, localAddress, localPort);
    }
}
