package org.leo.server.panama.vpn.configuration;

import java.util.Objects;

/**
 * @author xuyangze
 * @date 2018/10/10 下午3:16
 */
public class ShadowSocksConfiguration {

    /**
     * 启动模式
     * {@link ShadowSocksModeEnum}
     */
    private String mode;

    /**
     * encrypt: 加密 raw: 不加密
     * 加密信息： encrypt / raw
     */
    private String encrypt;

    /**
     * 加密类型
     */
    private String type;

    /**
     * 加密密码
     */
    private String password;

    /**
     * 启动端口
     */
    private int port;

    /**
     * 代理服务器地址
     */
    private String proxy;

    /**
     * 代理服务器端口
     */
    private int proxyPort;

    /**
     * 代理服务器加密类型
     */
    private String proxyType;

    /**
     * 代理服务器加密密码
     */
    private String proxyPassword;

    /**
     * 反向代理服务器地址
     */
    private String reverseHost;

    /**
     * 反向代理服务器端口
     */
    private int reversePort;


    public String getEncrypt() {
        if (null == encrypt) {
            return "encrypt";
        }
        return encrypt;
    }

    public void setEncrypt(String encrypt) {
        this.encrypt = encrypt;
    }

    public String getType() {
        if (null == type) {
            return "aes-256-cfb";
        }
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPassword() {
        if (null == password) {
            return "123456";
        }

        return password;
    }

    public int getPort() {
        if (0 == port) {
            return 9898;
        }

        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getReverseHost() {
        return reverseHost;
    }

    public void setReverseHost(String reverseHost) {
        this.reverseHost = reverseHost;
    }

    public int getReversePort() {
        return reversePort;
    }

    public void setReversePort(int reversePort) {
        this.reversePort = reversePort;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        this.proxy = proxy;
    }

    public String getProxyType() {
        return proxyType;
    }

    public void setProxyType(String proxyType) {
        this.proxyType = proxyType;
    }

    public String getProxyPassword() {
        return proxyPassword;
    }

    public void setProxyPassword(String proxyPassword) {
        this.proxyPassword = proxyPassword;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public boolean isProxyEqualsCurrent() {
        return Objects.equals(getProxyType(), getType()) && Objects.equals(getProxyPassword(), getPassword());
    }

    public String getMode() {
        if (null == mode) {
            return ShadowSocksModeEnum.NORMAL.getMode();
        }

        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
    public void validate() {
        String mode = getMode();
        if (ShadowSocksModeEnum.formMode(mode) == null) throw new IllegalArgumentException("Unknown mode: " + mode);
        if (!"inner".equals(mode)) checkPort(getPort());
        if ("proxy".equals(mode)) {
            if (getProxy() == null || getProxy().trim().isEmpty()) throw new IllegalArgumentException("proxy is required");
            checkPort(getProxyPort());
        }
        if ("inner".equals(mode) || "outer".equals(mode)) checkPort(getReversePort());
        if ("inner".equals(mode) && (getReverseHost() == null || getReverseHost().trim().isEmpty()))
            throw new IllegalArgumentException("reverseHost is required");
        if ("outer".equals(mode) && getPort() == getReversePort())
            throw new IllegalArgumentException("port and reversePort must differ");
        if (!java.util.Arrays.asList("raw", "encrypt", "compress", "zero-padding", "random-padding").contains(getEncrypt()))
            throw new IllegalArgumentException("Unknown transport wrapper");
        if ("encrypt".equals(getEncrypt())) {
            checkCipher(getType(), getPassword());
            if ("proxy".equals(mode) || "outer".equals(mode)) checkCipher(getProxyType(), getProxyPassword());
        }
    }
    private static void checkPort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
    }
    private static void checkCipher(String type, String password) {
        if (!java.util.Arrays.asList("aes-128-cfb", "aes-192-cfb", "aes-256-cfb", "aes-128-ofb", "aes-192-ofb", "aes-256-ofb", "bf-cfb").contains(type))
            throw new IllegalArgumentException("Unsupported cipher: " + type);
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("Password is required");
    }
}
