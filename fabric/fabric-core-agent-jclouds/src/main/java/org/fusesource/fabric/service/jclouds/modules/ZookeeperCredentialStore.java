/*
 * Copyright (C) FuseSource, Inc.
 *   http://fusesource.com
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.fusesource.fabric.service.jclouds.modules;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.fusesource.fabric.zookeeper.ZkPath;
import org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils;
import org.jclouds.domain.Credentials;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.karaf.core.CredentialStore;
import org.jclouds.rest.ConfiguresCredentialStore;
import org.linkedin.zookeeper.client.IZKClient;
import org.linkedin.zookeeper.client.LifecycleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link CredentialStore} backed by Zookeeper.
 * This module supports up to 100 node credential store in memory.
 * Credentials stored in memory will be pushed to Zookeeper when it becomes available.
 */
@ConfiguresCredentialStore
public class ZookeeperCredentialStore extends CredentialStore implements LifecycleListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZookeeperCredentialStore.class);

    private IZKClient zooKeeper;
    private Cache<String, Credentials> cache;

    public void init() {
        this.cache = CacheBuilder.newBuilder().maximumSize(100).build();
        this.store = new ZookeeperBacking(zooKeeper, cache);
    }

    /**
     * Configures a {@link com.google.inject.Binder} via the exposed methods.
     */
    @Override
    protected void configure() {
    }


    public IZKClient getZooKeeper() {
        return zooKeeper;
    }

    public void setZooKeeper(IZKClient zooKeeper) {
        this.zooKeeper = zooKeeper;
    }

    @Override
    public void onConnected() {
        //Whenever a connection to Zookeeper is made copy everything to Zookeeper.
        for (Map.Entry<String, Credentials> entry : cache.asMap().entrySet()) {
            String s = entry.getKey();
            Credentials credentials = entry.getValue();
            store.put(s, credentials);
        }
    }

    @Override
    public void onDisconnected() {

    }

    /**
     * A map implementations which uses a local {@link Cache} and Zookeeper as persistent store.
     */
    private class ZookeeperBacking implements Map<String, Credentials> {

        private final IZKClient zookeeper;
        private final Cache<String, Credentials> cache;

        private ZookeeperBacking(IZKClient zookeeper, Cache<String, Credentials> cache) {
            this.zookeeper = zookeeper;
            this.cache = cache;
        }

        /**
         * Returns the size of the store.
         * If zookeeper is connected it returns the size of the zookeeper store, else it falls back to the cache.
         * @return
         */
        public int size() {
            int size = 0;
            if (zookeeper.isConnected()) {
                try {
                    if (zookeeper.exists(ZkPath.CLOUD_NODES.getPath()) != null) {
                        size = zookeeper.getChildren(ZkPath.CLOUD_NODES.getPath()).size();
                    }
                } catch (Exception ex) {
                    //noop
                }
            } else {
               size = (int) cache.size();
            }
            return size;
        }

        public boolean isEmpty() {
            return size() == 0;
        }

        /**
         * Checks if {@link Cache} container the key and if not it checks the Zookeeper (if connected).
         * @param o
         * @return
         */
        public boolean containsKey(Object o) {
            boolean result  = cache.asMap().containsKey(o);
            //If not found in the cache check the zookeeper if available.
            if (!result) {
                if (zookeeper.isConnected()) {
                    try {
                        result = (zookeeper.exists(ZkPath.CLOUD_NODE.getPath(normalizeKey(o))) != null);
                    } catch (Exception ex) {
                        //noop
                    }
                }
            }
            return result;
        }

        /**
         * Never used, always returns false.
         * @param o
         * @return
         */
        public boolean containsValue(Object o) {
            return false;
        }

        /**
         * Gets the {@link Credentials} of the corresponding key from the {@link Cache}.
         * If the {@link Credentials} are not found, then it checks the Zookeeper.
         * @param o
         * @return
         */
        public Credentials get(Object o) {
            Credentials credentials = cache.asMap().get(o);
            if (credentials == null && zookeeper.isConnected()) {
                try {
                    String identity = zookeeper.getStringData(ZkPath.CLOUD_NODE_IDENTITY.getPath(normalizeKey(o)));
                    String credential = zookeeper.getStringData(ZkPath.CLOUD_NODE_CREDENTIAL.getPath(normalizeKey(o)));
                    credentials = LoginCredentials.fromCredentials(new Credentials(identity, credential));
                } catch (Exception e) {
                    LOGGER.debug("Failed to read jclouds credentials from zookeeper due to {}.", e.getMessage());
                }
            }
            return credentials;

        }

        /**
         * Puts {@link Credentials} both in {@link Cache} and the Zookeeper.
         * @param s
         * @param credentials
         * @return
         */
        public Credentials put(String s, Credentials credentials) {
            cache.put(s, credentials);
            if (zookeeper.isConnected()) {
                try {
                    ZooKeeperUtils.set(zookeeper, ZkPath.CLOUD_NODE_IDENTITY.getPath(normalizeKey(s)), credentials.identity);
                    ZooKeeperUtils.set(zookeeper, ZkPath.CLOUD_NODE_CREDENTIAL.getPath(normalizeKey(s)), credentials.credential);
                } catch (Exception e) {
                    LOGGER.warn("Failed to store jclouds credentials to zookeeper.", e);
                }
            }
            return credentials;
        }

        /**
         * Removes {@link Credentials} for {@link Cache} and Zookeeper.
         * @param o
         * @return
         */
        public Credentials remove(Object o) {
            Credentials credentials = cache.asMap().remove(o);
            if (zookeeper.isConnected()) {
                try {
                    if (credentials == null) {
                        credentials = get(o);
                    }
                    String normalizedKey = normalizeKey(o);
                    if (zookeeper.exists(ZkPath.CLOUD_NODE_IDENTITY.getPath(normalizedKey)) != null) {
                        zookeeper.deleteWithChildren(ZkPath.CLOUD_NODE_IDENTITY.getPath(normalizedKey));
                    }
                    if (zookeeper.exists(ZkPath.CLOUD_NODE_CREDENTIAL.getPath(normalizedKey)) != null) {
                        zookeeper.deleteWithChildren(ZkPath.CLOUD_NODE_CREDENTIAL.getPath(normalizedKey));
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to remove jclouds credentials to zookeeper.", e);
                }
            }
            return credentials;
        }

        /**
         * Puts all {@link Map} {@link Entry} to the {@link Cache} and Zookeeper.
         * @param map
         */
        public void putAll(Map<? extends String, ? extends Credentials> map) {
            for (Map.Entry<? extends String, ? extends Credentials> entry : map.entrySet()) {
                String s = entry.getKey();
                Credentials credential = entry.getValue();
                put(s, credential);
            }
        }

        public void clear() {
            cache.cleanUp();
            if (zookeeper.isConnected()) {
                try {
                    for (String nodeId : keySet()) {
                        if (zookeeper.exists(ZkPath.CLOUD_NODE_IDENTITY.getPath(nodeId)) != null) {
                            zookeeper.deleteWithChildren(ZkPath.CLOUD_NODE_IDENTITY.getPath(nodeId));
                        }
                        if (zookeeper.exists(ZkPath.CLOUD_NODE_CREDENTIAL.getPath(nodeId)) != null) {
                            zookeeper.deleteWithChildren(ZkPath.CLOUD_NODE_CREDENTIAL.getPath(nodeId));
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to clear zookeeper jclouds credentials store.", e);
                }
            }
        }

        /**
         * Clears {@link Cache} and Zookeeper from all {@link Credentials}.
         * @return
         */
        public Set<String> keySet() {
            Set<String> keys = new HashSet<String>();
            if (zookeeper.isConnected()) {
                try {
                    keys = new HashSet<String>(zookeeper.getChildren(ZkPath.CLOUD_NODE.getPath()));
                } catch (Exception e) {
                    LOGGER.warn("Failed to read from zookeeper jclouds credentials store.", e);
                }
            } else {
                keys = cache.asMap().keySet();
            }
            return keys;
        }

        public Collection<Credentials> values() {
            List<Credentials> credentialsList = new LinkedList<Credentials>();
            for (String key : keySet()) {
                credentialsList.add(get(key));

            }
            return credentialsList;
        }

        public Set<Map.Entry<String, Credentials>> entrySet() {
            Set<Map.Entry<String, Credentials>> entrySet = new HashSet<Entry<String, Credentials>>();
            if (zooKeeper.isConnected()) {
                for (String key : keySet()) {
                    entrySet.add(new CredentialsEntry(zookeeper, key));
                }
            } else {
                entrySet.addAll(cache.asMap().entrySet());
            }
            return entrySet;
        }
    }

    private static class CredentialsEntry implements Map.Entry<String, Credentials> {

        private String key;
        private IZKClient zookeeper;

        private CredentialsEntry(IZKClient zookeeper, String key) {
            this.zookeeper = zookeeper;
            this.key = key;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public Credentials getValue() {
            Credentials credentials = null;
            if (zookeeper.isConnected()) {
                try {
                    String identity = zookeeper.getStringData(ZkPath.CLOUD_NODE_IDENTITY.getPath(normalizeKey(key)));
                    String credential = zookeeper.getStringData(ZkPath.CLOUD_NODE_CREDENTIAL.getPath(normalizeKey(key)));
                    credentials = LoginCredentials.fromCredentials(new Credentials(identity, credential));
                } catch (Exception e) {
                    LOGGER.debug("Failed to read jclouds credentials from zookeeper due to {}.", e.getMessage());
                }
            }
            return credentials;
        }

        @Override
        public Credentials setValue(Credentials credentials) {
            if (zookeeper.isConnected()) {
                try {
                    ZooKeeperUtils.set(zookeeper, ZkPath.CLOUD_NODE_IDENTITY.getPath(normalizeKey(key)), credentials.identity);
                    ZooKeeperUtils.set(zookeeper, ZkPath.CLOUD_NODE_CREDENTIAL.getPath(normalizeKey(key)), credentials.credential);
                } catch (Exception e) {
                    LOGGER.warn("Failed to store jclouds credentials to zookeeper.", e);
                }
            }
            return credentials;
        }
    }

    private static String normalizeKey(Object key) {
        String result = String.valueOf(key);
        return result.replaceAll("node#", "").replaceAll("#","");
    }
}
