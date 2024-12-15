
/*******************************************************************************
* Copyright (c) 2009-2012 Luaj.org. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
* THE SOFTWARE.
******************************************************************************/

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;

import org.figuramc.luaj.vm2.Globals;
import org.figuramc.luaj.vm2.Lua;
import org.figuramc.luaj.vm2.lib.jse.JsePlatform;
import org.figuramc.luaj.vm2.luajc.LuaJC;

/**
 * Compiler for lua files to compile lua sources or lua binaries into java
 * classes.
 */
public class luajc {
	private static final String version = Lua._VERSION + " Copyright (C) 2012 luaj.org";

	private static final String usage = "usage: java -cp luaj-jse.jar,bcel-5.2.jar luajc [options] fileordir [, fileordir ...]\n"
		+ "Available options are:\n" + "  -        process stdin\n" + "  -s src	source directory\n"
		+ "  -d dir	destination directory\n" + "  -p pkg	package prefix to apply to all classes\n"
		+ "  -m		generate main(String[]) function for JSE\n" + "  -r		recursively compile all\n"
		+ "  -l		load classes to verify generated bytecode\n"
		+ "  -c enc  	use the supplied encoding 'enc' for input files\n" + "  -v   	verbose\n";

	private static void usageExit() {
		System.out.println(usage);
		System.exit(-1);
	}

	private String        srcdir      = ".";
	private String        destdir     = ".";
	private boolean       genmain     = false;
	private boolean       recurse     = false;
	private boolean       verbose     = false;
	private boolean       loadclasses = false;
	private String        encoding    = null;
	private String        pkgprefix   = null;
	private final List    files       = new ArrayList();
	private final Globals globals;

	public static void main(String[] args) throws IOException {
		new luajc(args);
	}

	private luajc(String[] args) throws IOException {

		// process args
		List seeds = new ArrayList();

		// get stateful args
		for (int i = 0; i < args.length; i++) {
			if (!args[i].startsWith("-")) {
				seeds.add(args[i]);
			} else {
				switch (args[i].charAt(1)) {
				case 's':
					if (++i >= args.length)
						usageExit();
					srcdir = args[i];
					break;
				case 'd':
					if (++i >= args.length)
						usageExit();
					destdir = args[i];
					break;
				case 'l':
					loadclasses = true;
					break;
				case 'p':
					if (++i >= args.length)
						usageExit();
					pkgprefix = args[i];
					break;
				case 'm':
					genmain = true;
					break;
				case 'r':
					recurse = true;
					break;
				case 'c':
					if (++i >= args.length)
						usageExit();
					encoding = args[i];
					break;
				case 'v':
					verbose = true;
					break;
				default:
					usageExit();
					break;
				}
			}
		}

		// echo version
		if (verbose) {
			System.out.println(version);
			System.out.println("srcdir: " + srcdir);
			System.out.println("destdir: " + destdir);
			System.out.println("files: " + seeds);
			System.out.println("recurse: " + recurse);
		}

		// need at least one seed
		if (seeds.size() <= 0) {
			System.err.println(usage);
			System.exit(-1);
		}

		// collect up files to process
		for (Object seed : seeds)
			collectFiles(srcdir + "/" + seed);

		// check for at least one file
		if (files.size() <= 0) {
			System.err.println("no files found in " + seeds);
			System.exit(-1);
		}

		// process input files
		globals = JsePlatform.standardGlobals();
		for (Object file : files)
			processFile((InputFile) file);
	}

	private void collectFiles(String path) {
		File f = new File(path);
		if (f.isDirectory() && recurse)
			scandir(f, pkgprefix);
		else if (f.isFile()) {
			File dir = f.getAbsoluteFile().getParentFile();
			if (dir != null)
				scanfile(dir, f, pkgprefix);
		}
	}

	private void scandir(File dir, String javapackage) {
		File[] f = dir.listFiles();
		for (File element : f)
			scanfile(dir, element, javapackage);
	}

	private void scanfile(File dir, File f, String javapackage) {
		if (f.exists()) {
			if (f.isDirectory() && recurse)
				scandir(f, javapackage != null? javapackage + "." + f.getName(): f.getName());
			else if (f.isFile() && f.getName().endsWith(".lua"))
				files.add(new InputFile(dir, f, javapackage));
		}
	}

	private static final class LocalClassLoader extends ClassLoader {
		private final Hashtable t;

		private LocalClassLoader(Hashtable t) {
			this.t = t;
		}

		@Override
		public Class findClass(String classname) throws ClassNotFoundException {
			byte[] bytes = (byte[]) t.get(classname);
			if (bytes != null)
				return defineClass(classname, bytes, 0, bytes.length);
			return super.findClass(classname);
		}
	}

	class InputFile {
		public String luachunkname;
		public String srcfilename;
		public File   infile;
		public File   outdir;
		public String javapackage;

		public InputFile(File dir, File f, String javapackage) {
			this.infile = f;
			String subdir = javapackage != null? javapackage.replace('.', '/'): null;
			String outdirpath = subdir != null? destdir + "/" + subdir: destdir;
			this.javapackage = javapackage;
			this.srcfilename = (subdir != null? subdir + "/": "")+infile.getName();
			this.luachunkname = (subdir != null? subdir + "/": "")
				+infile.getName().substring(0, infile.getName().lastIndexOf('.'));
			this.infile = f;
			this.outdir = new File(outdirpath);
		}
	}

	private void processFile(InputFile inf) {
		inf.outdir.mkdirs();
		try {
			if (verbose)
				System.out.println("chunk=" + inf.luachunkname + " srcfile=" + inf.srcfilename);

			// create the chunk
			FileInputStream fis = new FileInputStream(inf.infile);
			final Hashtable t = encoding != null
				? LuaJC.instance.compileAll(new InputStreamReader(fis, encoding), inf.luachunkname, inf.srcfilename,
					globals, genmain)
				: LuaJC.instance.compileAll(fis, inf.luachunkname, inf.srcfilename, globals, genmain);
			fis.close();

			// write out the chunk
			for (Enumeration e = t.keys(); e.hasMoreElements();) {
				String key = (String) e.nextElement();
				byte[] bytes = (byte[]) t.get(key);
				if (key.indexOf('/') >= 0) {
					String d = (destdir != null? destdir + "/": "")+key.substring(0, key.lastIndexOf('/'));
					new File(d).mkdirs();
				}
				String destpath = (destdir != null? destdir + "/": "") + key + ".class";
				if (verbose)
					System.out.println("  " + destpath + " (" + bytes.length + " bytes)");
				FileOutputStream fos = new FileOutputStream(destpath);
				fos.write(bytes);
				fos.close();
			}

			// try to load the files
			if (loadclasses) {
				ClassLoader loader = new LocalClassLoader(t);
				for (Enumeration e = t.keys(); e.hasMoreElements();) {
					String classname = (String) e.nextElement();
					try {
						Class c = loader.loadClass(classname);
						Object o = c.newInstance();
						if (verbose)
							System.out.println("    loaded " + classname + " as " + o);
					} catch (Exception ex) {
						System.out.flush();
						System.err.println("    failed to load " + classname + ": " + ex);
						System.err.flush();
					}
				}
			}

		} catch (Exception e) {
			System.err.println("    failed to load " + inf.srcfilename + ": " + e);
			e.printStackTrace(System.err);
			System.err.flush();
		}
	}
}
