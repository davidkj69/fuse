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
package org.fusesource.patch.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.fusesource.patch.Patch;
import org.fusesource.patch.Result;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.Version;
import org.osgi.framework.wiring.FrameworkWiring;

import static org.easymock.EasyMock.*;
import static org.junit.Assert.*;

public class ServiceImplTest {

    File baseDir;
    
    File storage;
    File bundlev131;
    File bundlev132;
    File bundlev140;
    File patch132;
    File patch140;
    
    @Before
    public void setUp() throws Exception {
        URL base = getClass().getClassLoader().getResource("log4j.properties");
        try {
            baseDir = new File(base.toURI()).getParentFile();
        } catch(URISyntaxException e) {
            baseDir = new File(base.getPath()).getParentFile();
        }
        
        generateData();
    }
    
    private void generateData() throws Exception {
        storage = new File(baseDir, "storage");
        delete(storage);
        storage.mkdirs();

        bundlev131 = createBundle("my-bsn", "1.3.1");
        bundlev132 = createBundle("my-bsn", "1.3.2");
        bundlev140 = createBundle("my-bsn", "1.4.0");
        
        patch132 = createPatch("patch-1.3.2", bundlev132);
        patch140 = createPatch("patch-1.4.0", bundlev140);
    }
    
    private File createPatch(String id, File bundle) throws Exception {
        File patchFile = new File(storage, "temp/" + id + ".zip");
        File pd = new File(storage, "temp/" + id + "/" + id + ".patch");
        pd.getParentFile().mkdirs();
        Properties props = new Properties();
        props.put("id", id);
        props.put("bundle.count", "1");
        props.put("bundle.0", bundle.toURI().toURL().toString());
        FileOutputStream fos = new FileOutputStream(pd);
        props.store(fos, null);
        fos.close();
        fos = new FileOutputStream(patchFile);
        jarDir(pd.getParentFile(), fos);
        fos.close();
        return patchFile;
    }
    
    private File createBundle(String bundleSymbolicName, String version) throws Exception {
        File jar = new File(storage, "temp/" + bundleSymbolicName + "-" + version + ".jar");
        File man = new File(storage, "temp/" + bundleSymbolicName + "-" + version + "/META-INF/MANIFEST.MF");
        man.getParentFile().mkdirs();
        Manifest mf = new Manifest();
        mf.getMainAttributes().putValue("Manifest-Version", "1.0");
        mf.getMainAttributes().putValue("Bundle-ManifestVersion", "2");
        mf.getMainAttributes().putValue("Bundle-SymbolicName", bundleSymbolicName);
        mf.getMainAttributes().putValue("Bundle-Version", version);
        FileOutputStream fos = new FileOutputStream(man);
        mf.write(fos);
        fos.close();
        fos = new FileOutputStream(jar);
        jarDir(man.getParentFile().getParentFile(), fos);
        fos.close();
        return jar;
    }
    
    @Test
    public void testPatch() throws Exception {

        BundleContext bundleContext = createMock(BundleContext.class);
        Bundle bundle0 = createMock(Bundle.class);
        Bundle bundle = createMock(Bundle.class);
        Bundle bundle2 = createMock(Bundle.class);
        FrameworkWiring wiring = createMock(FrameworkWiring.class);

        //
        // Create a new service, download a patch
        //

        expect(bundleContext.getProperty("fuse.patch.location"))
                .andReturn(storage.toString()).anyTimes();
        replay(bundleContext, bundle);

        ServiceImpl service = new ServiceImpl(bundleContext);
        Iterable<Patch> patches = service.download(patch132.toURI().toURL());
        assertNotNull(patches);
        Iterator<Patch> it = patches.iterator();
        assertTrue( it.hasNext() );
        Patch patch = it.next();
        assertNotNull( patch );
        assertEquals("patch-1.3.2", patch.getId());
        assertNotNull(patch.getBundles());
        assertEquals(1, patch.getBundles().size());
        Iterator<String> itb = patch.getBundles().iterator();
        assertEquals(bundlev132.toURI().toURL().toString(), itb.next());
        assertNull(patch.getResult());
        verify(bundleContext, bundle);

        //
        // Simulate the patch
        //

        reset(bundleContext, bundle);
        
        expect(bundleContext.getBundles()).andReturn(new Bundle[] { bundle });
        expect(bundle.getSymbolicName()).andReturn("my-bsn");
        expect(bundle.getVersion()).andReturn(new Version("1.3.1"));
        expect(bundle.getLocation()).andReturn("location");
        replay(bundleContext, bundle);
        
        Result result = patch.simulate();
        assertNotNull( result );
        assertNull( patch.getResult() ); 
        assertTrue(result.isSimulation());

        verify(bundleContext, bundle);

        //
        // Recreate a new service and verify the downloaded patch is still available
        //

        reset(bundleContext, bundle);
        expect(bundleContext.getProperty("fuse.patch.location"))
                .andReturn(storage.toString()).anyTimes();
        replay(bundleContext, bundle);

        service = new ServiceImpl(bundleContext);
        patches = service.getPatches();
        assertNotNull(patches);
        it = patches.iterator();
        assertTrue( it.hasNext() );
        patch = it.next();
        assertNotNull( patch );
        assertEquals("patch-1.3.2", patch.getId());
        assertNotNull(patch.getBundles());
        assertEquals(1, patch.getBundles().size());
        itb = patch.getBundles().iterator();
        assertEquals(bundlev132.toURI().toURL().toString(), itb.next());
        assertNull(patch.getResult());
        verify(bundleContext, bundle);

        // 
        // Install the patch
        //
        
        reset(bundleContext, bundle);

        expect(bundleContext.getBundles()).andReturn(new Bundle[] { bundle });
        expect(bundle.getSymbolicName()).andReturn("my-bsn");
        expect(bundle.getVersion()).andReturn(new Version("1.3.1"));
        expect(bundle.getLocation()).andReturn("location");
        bundle.uninstall();
        expect(bundleContext.installBundle(bundlev132.toURI().toURL().toString())).andReturn(bundle2);
        expect(bundleContext.getBundles()).andReturn(new Bundle[] { bundle2 });
        expect(bundle2.getState()).andReturn(Bundle.INSTALLED);
        expect(bundle2.getHeaders()).andReturn(new Hashtable());
        expect(bundle.getState()).andReturn(Bundle.UNINSTALLED);
        expect(bundleContext.getBundle(0)).andReturn(bundle0);
        expect(bundle0.adapt(FrameworkWiring.class)).andReturn(wiring);
        wiring.refreshBundles(eq(asSet(bundle2, bundle)), anyObject(FrameworkListener[].class));
        expectLastCall().andAnswer(new IAnswer<Object>() {
            @Override
            public Object answer() throws Throwable {
                for (FrameworkListener l : (FrameworkListener[]) (EasyMock.getCurrentArguments()[1])) {
                    l.frameworkEvent(null);
                }
                return null;
            }
        });
        replay(bundleContext, bundle0, bundle, bundle2, wiring);

        result = patch.install();
        assertNotNull( result );
        assertSame( result, patch.getResult() );
        assertFalse(patch.getResult().isSimulation());

        verify(bundleContext, bundle);

        //
        // Recreate a new service and verify the downloaded patch is still available and installed
        //

        reset(bundleContext, bundle);
        expect(bundleContext.getProperty("fuse.patch.location"))
                .andReturn(storage.toString()).anyTimes();
        replay(bundleContext, bundle);

        service = new ServiceImpl(bundleContext);
        patches = service.getPatches();
        assertNotNull(patches);
        it = patches.iterator();
        assertTrue( it.hasNext() );
        patch = it.next();
        assertNotNull( patch );
        assertEquals("patch-1.3.2", patch.getId());
        assertNotNull(patch.getBundles());
        assertEquals(1, patch.getBundles().size());
        itb = patch.getBundles().iterator();
        assertEquals(bundlev132.toURI().toURL().toString(), itb.next());
        assertNotNull(patch.getResult());
        verify(bundleContext, bundle);

    }

    private <T> Set<T> asSet(T... objects) {
        HashSet<T> set = new HashSet<T>();
        for (T t : objects) {
            set.add(t);
        }
        return set;
    }

    private void delete(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                delete( child );
            }
            file.delete();
        } else if (file.isFile()) {
            file.delete();
        }
    }

    private URL getZippedTestDir(String name) throws IOException {
        File f2 = new File(baseDir, name + ".jar");
        OutputStream os = new FileOutputStream(f2);
        jarDir(new File(baseDir, name), os);
        os.close();
        return f2.toURI().toURL();
    }


    public static void jarDir(File directory, OutputStream os) throws IOException
    {
        // create a ZipOutputStream to zip the data to
        JarOutputStream zos = new JarOutputStream(os);
        zos.setLevel(Deflater.NO_COMPRESSION);
        String path = "";
        File manFile = new File(directory, JarFile.MANIFEST_NAME);
        if (manFile.exists())
        {
            byte[] readBuffer = new byte[8192];
            FileInputStream fis = new FileInputStream(manFile);
            try
            {
                ZipEntry anEntry = new ZipEntry(JarFile.MANIFEST_NAME);
                zos.putNextEntry(anEntry);
                int bytesIn = fis.read(readBuffer);
                while (bytesIn != -1)
                {
                    zos.write(readBuffer, 0, bytesIn);
                    bytesIn = fis.read(readBuffer);
                }
            }
            finally
            {
                fis.close();
            }
            zos.closeEntry();
        }
        zipDir(directory, zos, path, Collections.singleton(JarFile.MANIFEST_NAME));
        // close the stream
        zos.close();
    }

    public static void zipDir(File directory, ZipOutputStream zos, String path, Set/* <String> */ exclusions) throws IOException
    {
        // get a listing of the directory content
        File[] dirList = directory.listFiles();
        byte[] readBuffer = new byte[8192];
        int bytesIn = 0;
        // loop through dirList, and zip the files
        for (int i = 0; i < dirList.length; i++)
        {
            File f = dirList[i];
            if (f.isDirectory())
            {
                String prefix = path + f.getName() + "/";
                zos.putNextEntry(new ZipEntry(prefix));
                zipDir(f, zos, prefix, exclusions);
                continue;
            }
            String entry = path + f.getName();
            if (!exclusions.contains(entry))
            {
                FileInputStream fis = new FileInputStream(f);
                try
                {
                    ZipEntry anEntry = new ZipEntry(entry);
                    zos.putNextEntry(anEntry);
                    bytesIn = fis.read(readBuffer);
                    while (bytesIn != -1)
                    {
                        zos.write(readBuffer, 0, bytesIn);
                        bytesIn = fis.read(readBuffer);
                    }
                }
                finally
                {
                    fis.close();
                }
            }
        }
    }
}
