package com.github.pms1.jdbctracing.api.core;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.github.pms1.jdbctracing.api.TracingCallback;
import com.github.pms1.jdbctracing.api.core.ds1.DS1;
import com.github.pms1.jdbctracing.api.core.ds1.DS2;
import com.github.pms1.jdbctracing.api.core.ds1.DS3;
import com.github.pms1.jdbctracing.tracers.DefaultTracingCallback;

public class FirstTest {

	@Rule
	public TemporaryFolder tfolder = new TemporaryFolder();

	private Path findClassPath(Class c) {
		String res = c.getName().replace('.', '/') + ".class";
		URL url = c.getClassLoader().getResource(res);
		if (url == null)
			throw new IllegalArgumentException("not found: " + res);

		if (!url.getProtocol().equals("file"))
			throw new UnsupportedOperationException();

		Path p;
		try {
			p = Paths.get(url.toURI());
		} catch (URISyntaxException e) {
			throw new UnsupportedOperationException(e);
		}

		Path resPath = Paths.get(res);
		if (!p.endsWith(resPath))
			throw new UnsupportedOperationException();

		for (int i = resPath.getNameCount(); i-- > 0;)
			p = p.getParent();

		return p;
	}

	private void copyPackage(Class<?> c) throws IOException, URISyntaxException {
		String res = c.getName().replace('.', '/') + ".class";

		URL url = c.getClassLoader().getResource(res);
		if (url == null)
			throw new IllegalArgumentException("not found: " + res);

		FileSystem fs;

		Path p;
		switch (url.getProtocol()) {
		case "file":
			try {
				p = Paths.get(url.toURI());
			} catch (URISyntaxException e) {
				throw new UnsupportedOperationException(e);
			}

			Path resPath = Paths.get(res);
			if (!p.endsWith(resPath))
				throw new UnsupportedOperationException();

			for (int i = resPath.getNameCount(); i-- > 0;)
				p = p.getParent();

			fs = null;
			break;
		case "jar":
			String spec = url.getFile();

			int separator = spec.indexOf("!/");
			/*
			 * REMIND: we don't handle nested JAR URLs
			 */
			if (separator == -1) {
				throw new MalformedURLException("no !/ found in url spec:" + spec);
			}

			URL jarFileURL = new URL(spec.substring(0, separator++));

			fs = FileSystems.newFileSystem(Paths.get(jarFileURL.toURI()), null);

			p = fs.getPath("/");

			break;

		default:
			throw new Error();
		}
		String r = c.getPackage().getName().replace('.', '/');
		Path pf = p.resolve(r);

		Path t = tfolder.getRoot().toPath();
		Path tf = t.resolve(r);

		Files.createDirectories(tf);

		Files.walkFileTree(pf, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				tf.resolve(pf.relativize(dir).toString());
				return super.preVisitDirectory(dir, attrs);
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.copy(file, tf.resolve(pf.relativize(file).toString()));
				return super.visitFile(file, attrs);
			}
		});

		if (fs != null)
			fs.close();
	}

	@Test
	public void t1() throws Exception {

		copyPackage(DS1.class);
		copyPackage(TracingCallback.class);
		copyPackage(DefaultTracingCallback.class);

		new InstumentationCore().run(tfolder.getRoot().toPath());

		URLClassLoader cl = new URLClassLoader(new URL[] { tfolder.getRoot().toURI().toURL() },
				ClassLoader.getSystemClassLoader().getParent());

		Class<?> c1 = cl.loadClass(DS1.class.getName());

		c1.newInstance();

		Class<?> c2 = cl.loadClass(DS2.class.getName());

		try {
			c2.newInstance();
		} catch (IllegalArgumentException e) {

		}

		Class<?> c3 = cl.loadClass(DS3.class.getName());

		try {
			c3.newInstance();
		} catch (IllegalArgumentException e) {

		}

		c1.newInstance();
	}
}
