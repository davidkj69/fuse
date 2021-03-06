/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.fabric.boot.commands.support;

import org.apache.karaf.shell.console.OsgiCommandSupport;
import org.fusesource.fabric.api.ZooKeeperClusterService;
import org.linkedin.zookeeper.client.IZKClient;
import org.osgi.framework.ServiceReference;

/**
 */
public abstract class EnsembleCommandSupport extends OsgiCommandSupport {
    protected ZooKeeperClusterService service;

    public ZooKeeperClusterService getService() {
        return service;
    }

    public void setService(ZooKeeperClusterService service) {
        this.service = service;
    }

    protected void checkFabricAvailable() {
        ServiceReference sr = getBundleContext().getServiceReference(IZKClient.class.getName());
        if (sr == null) {
            throw new IllegalStateException("No Fabric available, please create one using fabric:create or fabric:join.");
        }
    }

}
