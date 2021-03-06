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

package org.fusesource.fabric.fab.osgi.commands;

import org.apache.karaf.shell.console.OsgiCommandSupport;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.fusesource.fabric.fab.DependencyTree;
import org.fusesource.fabric.fab.osgi.FabBundleInfo;
import org.fusesource.fabric.fab.osgi.FabResolverFactory;
import org.fusesource.fabric.fab.osgi.FabURLHandler;
import org.fusesource.fabric.fab.osgi.internal.BundleFabFacade;
import org.fusesource.fabric.fab.osgi.internal.FabClassPathResolver;
import org.fusesource.fabric.fab.osgi.internal.FabConnection;
import org.fusesource.fabric.fab.osgi.internal.FabFacade;
import org.fusesource.fabric.fab.util.Filter;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.packageadmin.PackageAdmin;
import org.sonatype.aether.RepositoryException;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;

public abstract class CommandSupport extends OsgiCommandSupport {
    private PackageAdmin admin;
    private FabResolverFactory factory;

    protected PackageAdmin getPackageAdmin() {
        if (admin == null) {
            ServiceReference ref = getBundleContext().getServiceReference(PackageAdmin.class.getName());
            if (ref == null) {
                System.out.println("PackageAdmin service is unavailable.");
                return null;
            }
            // using the getService call ensures that the reference will be released at the end
            admin = getService(PackageAdmin.class, ref);
        }
        return admin;
    }

    protected FabResolverFactory getFabResolverFactory() {
        if (factory == null) {
            ServiceReference ref = getBundleContext().getServiceReference(FabResolverFactory.class.getName());
            if (ref == null) {
                System.out.println("FabResolverFactory service is unavailable.");
                return null;
            }
            // using the getService call ensures that the reference will be released at the end
            factory = getService(FabResolverFactory.class, ref);
        }
        return factory;
    }

    protected void println() {
        session.getConsole().println();
    }

    protected void println(String msg, Object... args) {
        session.getConsole().println(String.format(msg, args));
    }

    protected FabClassPathResolver createFabResolver(Bundle bundle) throws RepositoryException, IOException, XmlPullParserException, BundleException {
        Properties instructions = new Properties();
        Dictionary headers = bundle.getHeaders();
        Enumeration e = headers.keys();
        while (e.hasMoreElements()) {
            Object key = e.nextElement();
            Object value = headers.get(key);
            if (key instanceof String && value instanceof String) {
                instructions.setProperty((String) key, (String) value);
            }
        }

        FabFacade facade = new BundleFabFacade(bundle);
        Map<String, Object> embeddedResources = new HashMap<String, Object>();
        FabClassPathResolver resolver = new FabClassPathResolver(facade, instructions, embeddedResources);
        resolver.resolve();
        return resolver;
    }

    protected FabURLHandler findURLHandler() throws InvalidSyntaxException {
        ServiceReference[] references = bundleContext.getServiceReferences("org.osgi.service.url.URLStreamHandlerService", null);
        for (ServiceReference reference : references) {
            Object service = bundleContext.getService(reference);
            if (service instanceof FabURLHandler) {
                return (FabURLHandler) service;
            }
        }
        return null;
    }

    protected FabClassPathResolver createResolver(String arg) throws RepositoryException, IOException, XmlPullParserException, BundleException, InvalidSyntaxException {
        FabClassPathResolver resolver = null;
        if (arg.matches("\\d+")) {
            Bundle bundle = getBundle(arg);
            if (bundle != null) {
                resolver = createFabResolver(bundle);
            }
        } else {
            FabURLHandler handler = findURLHandler();
            if (handler != null) {
                File file = new File(arg);
                String u = arg;
                if (file.exists()) {
                    u = file.toURI().toURL().toString();
                }
                if (!arg.startsWith("fab:")) {
                    u = "fab:" + u;
                }
                FabConnection urlConnection = handler.openConnection(new URL(u));
                resolver = urlConnection.resolve();
            } else {
                println("ERROR: could not resolve FabURLHandler service in OSGi");
            }
        }
        return resolver;
    }

    /**
     * Get the bundle by id, printing a nice error message if the bundle id is invalid
     */
    protected Bundle getBundle(String id) {
        try {
            return getBundle(Long.parseLong(id));
        } catch (NumberFormatException e) {
            System.err.println("Failed to parse bundle ID: " + id + ". Reason: " + e);
            return null;
        }
    }

    /**
     * Get the bundle by id, printing a nice error message if the bundle id is invalid
     */
    protected Bundle getBundle(long id) {
        Bundle bundle = bundleContext.getBundle(id);
        if (bundle == null) {
            System.err.println("Bundle ID " + id + " is invalid");
        }
        return bundle;
    }

    protected FabBundleInfo getFabBundleInfo(String url) {
        try {
            FabBundleInfo info = getFabResolverFactory().getResolver(new URL(url)).getInfo();
            info.getInputStream();
            return info;
        } catch (Exception e) {
            System.err.println("Unable to retrieve FAB info for " + url + ". Reason: " + e);
        }
        return null;
    }

    public static class Table {
        final String format;
        private final int[] col;
        final ArrayList<ArrayList<Object>> table = new ArrayList<ArrayList<Object>>();

        public Table(String format, int... col) {
            this.format = format;
            this.col = col;
        }

        public void add(Object... values) {
            if (values.length != col.length) {
                throw new IllegalArgumentException("Expected " + col.length + " arguments");
            }
            table.add(new ArrayList<Object>(Arrays.asList(values)));
        }

        public void print(PrintStream out) {
            String fmt = format;
            for (int i = 0; i < col.length; i++) {
                String token = "{" + (i + 1) + "}";
                if (fmt.contains(token)) {
                    if (col[i] != 0) {
                        int len = Math.abs(col[i]);
                        for (ArrayList<Object> row : table) {
                            Object o = row.get(i);
                            if (o == null) {
                                o = "";
                            }
                            String s = o.toString();
                            row.set(i, s);
                            len = Math.max(s.length(), len);
                        }
                        if (col[i] < 0) {
                            len *= -1;
                        }
                        fmt = fmt.replaceAll(Pattern.quote(token), "%" + len + "s");
                    } else {
                        fmt = fmt.replaceAll(Pattern.quote(token), "%s");
                    }
                }
            }
            for (ArrayList<Object> row : table) {
                out.println(String.format(fmt, row.toArray()));
            }
        }
    }


}