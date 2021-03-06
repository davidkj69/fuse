package org.fusesource.fabric.boot.commands.service;


import org.fusesource.fabric.zookeeper.IZKClient;
import org.osgi.service.cm.ConfigurationAdmin;

/**
 *
 */

public interface Join {
    Object run() throws Exception;

    void setConfigurationAdmin(ConfigurationAdmin configurationAdmin);

    void setZooKeeper(IZKClient zooKeeper);

    String getVersion();

    void setVersion(String version);

    String getZookeeperUrl();

    void setZookeeperUrl(String zookeeperUrl);
}
