package org.leo.server.panama.vpn.configuration;

import com.alibaba.fastjson.JSON;
import org.leo.server.panama.vpn.util.FileUtils;
/**
 * @author xuyangze
 * @date 2018/11/22 4:27 PM
 */
public class ConfigurationReader {
    private static final String CONFIG_FILE_NAME = "panama.config";

    public static ShadowSocksConfiguration read() {
        return read(null);
    }

    public static ShadowSocksConfiguration read(String configFileName) {
        if (null == configFileName || configFileName.length() == 0) {
            configFileName = CONFIG_FILE_NAME;
        }

        String config = FileUtils.read(configFileName, null);
        ShadowSocksConfiguration shadowSocksConfiguration = JSON.parseObject(config, ShadowSocksConfiguration.class);
        if (shadowSocksConfiguration == null) throw new IllegalArgumentException("Configuration must be a JSON object");
        shadowSocksConfiguration.validate();
        return shadowSocksConfiguration;
    }

    public static void main(String []args) {
        ShadowSocksConfiguration shadowSocksConfiguration = read();
        System.out.println("Configuration valid: mode=" + shadowSocksConfiguration.getMode() + ", port=" + shadowSocksConfiguration.getPort());
    }
}
