// Copyright 2009 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Main class for a JAR file to run code from "WEB-INF/lib". */
public class ExecutableWarMain {
  private static final String EXECUTABLE_WAR_PACKAGE = "Executable-War-Package";
  private static final String myURL = myURL();

  public static void main(final String argv[]) throws Exception {
    if (argv.length > 0) {
      final String action = argv[0];
      if ("-p".equals(action) || "--cat".equals(action)) {
        // Copy the contents of a file to System.out
        //
        if (argv.length == 2) {
          cat(argv[1]);
        } else {
          System.err.println("usage: [-p|--cat] <filename>");
          System.exit(1);
        }
        return;

      } else if ("-l".equals(action) || "--ls".equals(action)) {
        // List the available files under WEB-INF/
        //
        if (argv.length == 1) {
          ls();
        } else {
          System.err.println("usage: [-l|--ls]");
          System.exit(1);
        }
        return;

      } else if (!action.startsWith("-")) {
        // Run an arbitrary application class
        //
        final ClassLoader cl = fullClassLoader();
        Thread.currentThread().setContextClassLoader(cl);
        runMain(cl, argv);
        return;
      }
    }

    System.err.println("usage: CommandName [args...]");
    System.err.println("usage: [-p|--cat] <filename>");
    System.err.println("usage: [-l|--ls]");
    System.exit(1);
  }

  private static void cat(String fileName) throws IOException {
    while (fileName.startsWith("/")) {
      fileName = fileName.substring(1);
    }

    final InputStream in =
        ExecutableWarMain.class.getResourceAsStream("WEB-INF/" + fileName);
    if (in == null) {
      System.err.println("error: no such file " + fileName);
      System.exit(1);
    }

    try {
      try {
        final byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
          System.out.write(buf, 0, n);
        }
      } finally {
        System.out.flush();
      }
    } finally {
      in.close();
    }
  }

  private static void ls() throws IOException {
    final ZipInputStream zf = openMyZip();
    try {
      ZipEntry ze;
      while ((ze = zf.getNextEntry()) != null) {
        boolean show = true;
        show &= ze.getName().startsWith("WEB-INF/");
        show &= !ze.isDirectory();
        show &= !ze.getName().startsWith("WEB-INF/lib/");
        show &= !ze.getName().equals("WEB-INF/web.xml");
        zf.closeEntry();
        if (show) {
          System.out.println(ze.getName().substring("WEB-INF/".length()));
        }
      }
    } finally {
      zf.close();
    }
  }

  private static void runMain(final ClassLoader loader, final String[] origArgv)
      throws Exception {
    String name = origArgv[0];
    final String[] argv = new String[origArgv.length - 1];
    System.arraycopy(origArgv, 1, argv, 0, argv.length);

    final Attributes att = myManifest();
    String pkg = att.getValue(EXECUTABLE_WAR_PACKAGE);
    if (pkg == null) {
      pkg = "";
    } else {
      pkg = pkg + ".";
    }

    Class<?> clazz;
    try {
      try {
        clazz = Class.forName(pkg + name, true, loader);
      } catch (ClassNotFoundException cnfe) {
        if (name.equals(name.toLowerCase())) {
          final String nUC = name.substring(0, 1).toUpperCase();
          clazz = Class.forName(pkg + nUC + name.substring(1), true, loader);
        } else {
          throw cnfe;
        }
      }
    } catch (ClassNotFoundException cnfe) {
      System.err.println("fatal: unknown command " + name);
      System.err.println("      (Class " + pkg + name + " not found)");
      System.exit(1);
      return;
    }

    final Method main;
    try {
      main = clazz.getMethod("main", argv.getClass());
    } catch (SecurityException e) {
      System.err.println("fatal: unknown command " + name);
      System.exit(1);
      return;
    } catch (NoSuchMethodException e) {
      System.err.println("fatal: unknown command " + name);
      System.exit(1);
      return;
    }

    main.invoke(null, new Object[] {argv});
  }

  private static Attributes myManifest() throws IOException,
      MalformedURLException {
    final InputStream mfin =
        new URL("jar:" + myURL + "!/META-INF/MANIFEST.MF").openStream();
    try {
      return new Manifest(mfin).getMainAttributes();
    } finally {
      mfin.close();
    }
  }

  private static ClassLoader fullClassLoader() {
    final ArrayList<URL> paths = new ArrayList<URL>();
    try {
      final ZipInputStream zf = openMyZip();
      try {
        ZipEntry ze;
        while ((ze = zf.getNextEntry()) != null) {
          if (ze.getName().startsWith("WEB-INF/lib/")) {
            // Try to derive the name of the temporary file so it
            // doesn't completely suck. Best if we can make it
            // match the name it was in the WAR.
            //
            String name = ze.getName().substring("WEB-INF/lib/".length());
            if (name.lastIndexOf('/') >= 0) {
              name = name.substring(name.lastIndexOf('/') + 1);
            }
            if (name.lastIndexOf('.') >= 0) {
              name = name.substring(0, name.lastIndexOf('.'));
            }
            if (name.length() == 0) {
              name = "ewar";
            }

            final File tmp = File.createTempFile(name, "jar");
            tmp.deleteOnExit();
            final FileOutputStream out = new FileOutputStream(tmp);
            try {
              final byte[] buf = new byte[4096];
              int n;
              while ((n = zf.read(buf, 0, buf.length)) > 0) {
                out.write(buf, 0, n);
              }
            } finally {
              out.close();
            }
            paths.add(tmp.toURL());
          }
          zf.closeEntry();
        }
      } finally {
        zf.close();
      }
    } catch (IOException e) {
      throw new LinkageError("Cannot unpack libs from " + myURL);
    }
    if (paths.isEmpty()) {
      throw new LinkageError("No files under WEB-INF/lib/");
    }
    return new URLClassLoader(paths.toArray(new URL[paths.size()]));
  }

  private static ZipInputStream openMyZip() throws IOException,
      MalformedURLException {
    final URL u = new URL(myURL);
    return new ZipInputStream(new BufferedInputStream(u.openStream()));
  }

  private static String myURL() {
    final String myName =
        ExecutableWarMain.class.getName().replace('.', '/') + ".class";
    final URL u = ExecutableWarMain.class.getClassLoader().getResource(myName);
    if (u == null) {
      throw new LinkageError("Cannot locate " + myName);
    }

    if (!"jar".equals(u.getProtocol())) {
      throw new LinkageError("Expected jar: URL: " + u);
    }

    String path = u.toExternalForm();
    if (path == null) {
      throw new LinkageError("Expected jar: URL: " + u);
    }

    final int bang = path.indexOf('!');
    if (bang < 0) {
      throw new LinkageError("Expected jar: URL: " + u);
    }

    return path.substring(4, bang);
  }
}
