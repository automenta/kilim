/* Copyright (c) 2006, Sriram Srinivasan, 2016 nqzero
 *
 * You may distribute this software under the terms of the license 
 * specified in the file "License"
 */

package kilim.tools;

import kilim.KilimException;
import kilim.WeavingClassLoader;
import kilim.analysis.ClassInfo;
import kilim.analysis.ClassWeaver;
import kilim.analysis.FileLister;
import kilim.analysis.KilimContext;
import kilim.mirrors.CachedClassMirrors;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * This class supports both command-line and run time weaving of Kilim bytecode. 
 */

public class Weaver {
    public static boolean dbg = false;
    public static String outputDir = null;
    public static boolean verbose = true;
    public static boolean force = false;
    public static boolean proxy = true;
    public static Pattern excludePattern = null;
    static int err = 0;

    public KilimContext context;
    public Weaver(KilimContext $context) {
        context = $context==null ? new KilimContext() : $context;
    }
    
    
    
    
    /**
     * <pre>
     * Usage: java kilim.tools.Weaver -d &lt;output directory&gt; {source classe, jar, directory ...}
     * </pre>
     * 
     * If directory names or jar files are given, all classes in that container are processed. It is
     * perfectly fine to specify the same directory for source and output like this: 
     * <pre>
     *    java kilim.tools.Weaver -d ./classes ./classes
     * </pre>
     * 
     * by default, each element is added to the classpath (use -c to suppress classpath augmentation)
     * 
     * arguments:
     * <ul>
     * <li>-d directory: write output to directory (required)</li>
     * <li>-f: force, write output even if output file is newer than source</li>
     * <li>-c: don't add source class list to the classpath</li>
     * <li>-h: print help info</li>
     * <li>-q: quiet</li>
     * <li>-x regex: exclude, skip classes matching regex</li>
     * </ul>
     * 
     * Ensure that all classes to be woven are in the classpath. The output directory does not have to be 
     * in the classpath during weaving.
     *   
     * @see #weave(List) for run-time weaving.
     */
    public static void main(String[] args) throws IOException {
        ArrayList<String> names = parseArgs(args);
        doMain(names.toArray(new String[names.size()]),null);
        if (err > 0) System.exit(err);
    }
    private static String [] concat(String [] a,String [] b) {
        String [] c = new String[a.length + b.length];
        System.arraycopy(a,0,c,0,a.length);
        System.arraycopy(b,0,c,a.length,b.length);
        return c;
    }
    
    public static int doMain(String [] names,String [] classpath) throws IOException {
        mkdir(outputDir);
        
        Weaver weaver;
        if (proxy) {
            ClassLoader current = Weaver.class.getClassLoader();
            String [] composite = classpath==null ? names : concat(names,classpath);
            URL [] paths = WeavingClassLoader.getURLs(composite);
            CachedClassMirrors ccm = new CachedClassMirrors(null,new URLClassLoader(paths,current));
            weaver = new Weaver(new KilimContext(ccm));
        }
        else
            weaver = new Weaver(null);

        String currentName = null;
        for (String name : names) {
            try {
                if (name.endsWith(".class")) {
                    if (exclude(name))
                        continue;
                    currentName = name;
                    weaver.weaveFile(name, new BufferedInputStream(new FileInputStream(name)));
                } else if (name.endsWith(".jar")) {
                    for (FileLister.Entry fe : new FileLister(name)) {
                        currentName = fe.getFileName();
                        if (currentName.endsWith(".class")) {
                            currentName = currentName.substring(0, currentName.length() - 6)
                                    .replace('/', '.');
                            if (exclude(currentName))
                                continue;
                            weaver.weaveFile(currentName, fe.getInputStream());
                        }
                    }
                } else if (new File(name).isDirectory()) {
                    for (FileLister.Entry fe : new FileLister(name)) {
                        currentName = fe.getFileName();
                        if (currentName.endsWith(".class")) {
                            if (exclude(currentName))
                                continue;
                            if (!force && fe.check(outputDir))
                                continue;
                            weaver.weaveFile(currentName, fe.getInputStream());
                        }
                    }
                } else {
                    System.out.println("skipping class (support removed): " + name);
                }
            } catch (KilimException ke) {
                System.err.println("Error weaving " + currentName + ". " + ke.getMessage());
                // ke.printStackTrace();
                System.exit(1);
            } catch (IOException ioe) {
                System.err.println("Unable to find/process '" + currentName + '\'');
                System.exit(1);
            } catch (Throwable t) {
                System.err.println("Error weaving " + currentName);
                t.printStackTrace();
                System.exit(1);
            }
        }
        return err;
    }

    static boolean exclude(String name) {
        return excludePattern != null && excludePattern.matcher(name).find();
    }

    // non-static to allow easy usage from alternative classloaders
    public ClassWeaver weave(InputStream is) {
        if (is==null) return null;
        ClassWeaver cw = null;
        try {
            cw = new ClassWeaver(context,is);
            cw.weave();
        }
        catch (IOException ex) {}
        return cw;
    }

    public void weaveFile(String name, InputStream is) {
        try {
            ClassWeaver cw = new ClassWeaver(context,is);
            cw.weave();
            writeClasses(cw);
        } catch (KilimException ke) {
            System.err.println("***** Error weaving " + name + ". " + ke.getMessage());
            // ke.printStackTrace();
            err = 1;
        } catch (RuntimeException re) {
            System.err.println("***** Error weaving " + name + ". " + re.getMessage());
            re.printStackTrace();
            err = 1;
        } catch (IOException ioe) {
            err = 1;
            System.err.println("***** Unable to find/process '" + name + "'\n" + ioe.getMessage());
        }
    }



    static void writeClasses(ClassWeaver cw) throws IOException {
        List<ClassInfo> cis = cw.getClassInfos();
        if (!cis.isEmpty()) {
            for (ClassInfo ci : cis) {
                writeClass(ci);
            }
        }
    }

    public static void writeClass(ClassInfo ci) throws IOException, FileNotFoundException {
        String className = ci.className.replace('.', File.separatorChar);
        String dir = outputDir + File.separatorChar + getDirName(className);
        mkdir(dir);
        // Convert name to fully qualified file name
        className = outputDir + File.separatorChar + className + ".class";
        if (ci.className.startsWith("kilim.S_")) {
            // Check if we already have that file
            if (new File(className).exists())
                return;
        }
        FileOutputStream fos = new FileOutputStream(className);
        fos.write(ci.bytes);
        fos.close();
        if (verbose) {
            System.out.println("Wrote: " + className);
        }
    }

    static void mkdir(String dir) throws IOException {
        File f = new File(dir);
        if (!f.exists()) {
            if (!f.mkdirs()) {
                throw new IOException("Unable to create directory: " + dir);
            }
        }
    }

    static String getDirName(String className) {
        int end = className.lastIndexOf(File.separatorChar);
        return (end == -1) ? "" : className.substring(0, end);
    }

    static void help() {
        System.err.println("java kilim.tools.Weaver opts -d <outputDir> (class/directory/jar)+");
        System.err.println("   where opts are   -q : quiet");
        System.err.println("                    -x <regex> : exclude all classes matching regex");
        System.err.println("                    -f         : weave even if up to date");
        System.err.println("                    -c         : don't add targets to classpath");
        System.exit(1);
    }

    static ArrayList<String> parseArgs(String[] args) {
        if (args.length == 0)
            help();

        ArrayList<String> ret = new ArrayList<>(args.length);
        String regex = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-d":
                    outputDir = args[++i];
                    break;
                case "-q":
                    verbose = false;
                    break;
                case "-f":
                    force = true;
                    break;
                case "-c":
                    proxy = false;
                    break;
                case "-h":
                    help();
                    break;
                case "-x":
                    regex = args[++i];
                    excludePattern = Pattern.compile(regex);
                    break;
                default:
                    ret.add(arg);
                    break;
            }
        }
        if (outputDir == null) {
            System.err.println("Specify output directory with -d option");
            System.exit(1);
        }
        return ret;
    }



    /**
     * Analyzes the list of supplied classes and inserts Kilim-related bytecode if necessary. If a
     * supplied class is dependent upon another class X, it is the caller's responsibility to ensure
     * that X is either in the classpath, or loaded by the context classloader, or has been seen in
     * an earlier invocation of weave().  
     * 
     * Since weave() remembers method signatures from earlier invocations, the woven classes do not
     * have to be classloaded to help future invocations of weave. 
     * 
     * If two classes A and B are not in the classpath, and are mutually recursive, they can be woven
     * only if supplied in the same input list.
     *  
     * This method is thread safe.
     * 
     * @param classes A list of (className, byte[]) pairs. The first part is a fully qualified class
     *            name, and the second part is the bytecode for the class.
     * 
     * @return A list of (className, byte[]) pairs. Some of the classes may or may not have been
     *         modified, and new ones may be added.
     * 
     * @throws KilimException
     */
    public List<ClassInfo> weave(List<ClassInfo> classes) throws KilimException, IOException {
        // save the detector attached to this thread, if any. It will be restored
        // later.
        ArrayList<ClassInfo> ret = new ArrayList<>(classes.size());
        try {
            // First cache all the method signatures from the supplied classes to allow
            // the weaver to lookup method signatures from mutually recursive classes.
            for (ClassInfo cl : classes) {
                context.detector.mirrors.mirror(cl.bytes);
            }

            // Now weave them individually
            for (ClassInfo cl : classes) {
                InputStream is = new ByteArrayInputStream(cl.bytes);
                ClassWeaver cw = new ClassWeaver(context,is);
                cw.weave();
                ret.addAll(cw.getClassInfos()); // one class file can result in multiple classes
            }
            return ret;
        } finally {
        }
    }

    
    

}
