package com.github.pms1.jdbctracing.api.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class InstumentationCore {

	static class Entry {
		String name;
		ClassReader cr;
	}

	static abstract class AccessibleObjectMetadata {
		private final int access;

		protected AccessibleObjectMetadata(int access) {
			this.access = access;
		}

		boolean isPublic() {
			return (access & Opcodes.ACC_PUBLIC) != 0;
		}
	}

	static class MethodMetadata extends AccessibleObjectMetadata {
		private MethodSignature signature;

		public MethodMetadata(int access, MethodSignature signature) {
			super(access);
			this.signature = signature;
		}

		public MethodSignature getSignature() {
			return signature;
		}

		@Override
		public String toString() {
			return signature.toString();
		}
	}

	static class ClassHierarchy {
		Map<String, ClassMetadata> classes = new HashMap<>();
		Map<ClassReader, ClassMetadata> byReader = new HashMap<>();

		void add(ClassReader reader, ClassMetadata cmd) {
			Objects.requireNonNull(cmd);

			ClassMetadata old = classes.putIfAbsent(cmd.name, cmd);
			if (old != null)
				throw new IllegalArgumentException();
			old = byReader.putIfAbsent(reader, cmd);
			if (old != null)
				throw new IllegalArgumentException();
		}

		boolean hasSuperClass(String name, String cand) {
			ClassMetadata classMetadata = classes.get(name);
			if (classMetadata == null)
				return false;
			if (Objects.equals(classMetadata.superName, cand))
				return true;
			if (classMetadata.interfaces.contains(cand))
				return true;
			if (classMetadata.superName != null && hasSuperClass(classMetadata.superName, cand))
				return true;
			for (String i : classMetadata.interfaces)
				if (hasSuperClass(i, cand))
					return true;
			return false;
		}

		public Set<ClassMetadata> getHierarchy(ClassMetadata c) {
			Set<ClassMetadata> r = new HashSet<>();
			r.add(c);
			if (c.superName != null)
				r.addAll(getHierarchy(c.superName));
			for (String i : c.interfaces)
				r.addAll(getHierarchy(i));
			return r;

		}

		private Collection<ClassMetadata> getHierarchy(String superName) {
			ClassMetadata classMetadata = classes.get(superName);
			if (classMetadata == null)
				return Collections.emptySet();
			else
				return getHierarchy(classMetadata);
		}

		public ClassMetadata getSuperClass(ClassMetadata c1) {
			if (c1.superName != null)
				return classes.get(c1.superName);
			else
				return null;
		}

		public void addLibrary(ClassReader r, ClassMetadata cmd) {
			add(r, cmd);
			libraryClasses.add(cmd);
		}

		private Set<ClassMetadata> libraryClasses = new HashSet<>();

		boolean isLibrary(ClassMetadata cmd) {
			return libraryClasses.contains(cmd);
		}

		public ClassMetadata get(String l1) {
			ClassMetadata result = classes.get(l1);
			if (result == null)
				throw new Error();
			return result;
		}
	}

	enum ClassType {
		CLASS, INTERFACE, ABSTRACT
	}

	static class ClassMetadata {
		private final String name;
		private final ClassType type;
		private final String superName;
		private final List<String> interfaces;

		private final List<MethodMetadata> methods2;

		ClassMetadata(String name, ClassType type, String superName, List<String> interfaces,
				List<MethodMetadata> methods2) {
			this.name = name;
			this.type = type;
			this.superName = superName;
			this.interfaces = interfaces;
			this.methods2 = methods2;
		}

		@Override
		public String toString() {
			return name;
		}

		public List<MethodMetadata> getMethods() {
			return methods2;
		}
	}

	public static class MethodSignature {
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((description == null) ? 0 : description.hashCode());
			result = prime * result + ((name == null) ? 0 : name.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			MethodSignature other = (MethodSignature) obj;
			if (description == null) {
				if (other.description != null)
					return false;
			} else if (!description.equals(other.description))
				return false;
			if (name == null) {
				if (other.name != null)
					return false;
			} else if (!name.equals(other.name))
				return false;
			return true;
		}

		public final String name;
		public final String description;

		public MethodSignature(String name, String description) {
			this.name = name;
			this.description = description;
		}

		@Override
		public String toString() {
			return name + " " + description;
		}
	}

	static class ScanClassVisitor extends ClassVisitor {

		public ScanClassVisitor() {
			super(Opcodes.ASM5);
		}

		public ClassMetadata cmd;
		private ClassType type;
		private String name;
		private String superName;
		private List<String> interfaces;
		private List<MethodMetadata> methods = new ArrayList<>();

		@Override
		public void visitEnd() {
			cmd = new ClassMetadata(name, type, superName, interfaces, methods);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
			methods.add(new MethodMetadata(access, new MethodSignature(name, desc)));
			return super.visitMethod(access, name, desc, signature, exceptions);
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName,
				String[] interfaces) {
			super.visit(version, access, name, signature, superName, interfaces);

			ClassType type;
			if ((access & Opcodes.ACC_INTERFACE) != 0)
				type = ClassType.INTERFACE;
			else if ((access & Opcodes.ACC_ABSTRACT) != 0)
				type = ClassType.ABSTRACT;
			else
				type = ClassType.CLASS;

			this.type = type;
			this.name = name;
			this.superName = superName;
			this.interfaces = Arrays.asList(interfaces);
		}

	}

	interface Writer {
		void write(String path, byte[] content) throws IOException;
	}

	public static void main(String[] args) throws IOException {

		if (true) {

			run(Paths.get("C:/Users/Mirko/git/jdbc-tracing/jdbc-tracing-maven-plugin/target/it/test1/target/classes"));

		} else {
			Path in = Paths.get("W:/work/workspaces/fisng/workspace/tracing-db/target/tracing-db-0.0.1-SNAPSHOT.jar");
			Path out = Paths.get("c:/temp/foo.jar");

			Manifest manifest = null;
			try (InputStream is = Files.newInputStream(in); JarInputStream jis = new JarInputStream(is);) {

				manifest = jis.getManifest();

				try (OutputStream os = Files.newOutputStream(out);
						JarOutputStream jos = new JarOutputStream(os, manifest)) {

					// ZipEntry ze = new ZipEntry(e.name);
					// jos.putNextEntry(ze);

					// jos.write(cw.toByteArray());

				}
			}
		}
	}

	static class Resource {
		String name;
		Supplier<InputStream> is;
	}

	static void process(ClassLoader classLoader, Iterable<Resource> resources, Writer writer) throws IOException {
		// Path in =
		// Paths.get("W:/work/workspaces/fisng/workspace/tracing-db/target/tracing-db-0.0.1-SNAPSHOT.jar");

		List<Entry> entries = new ArrayList<>();
		ClassHierarchy ch = new ClassHierarchy();

		System.err.println("X");
		for (Resource r : resources) {
			System.err.println("ENTRY " + r.name);
			if (r.name.endsWith(".class")) {
				ClassReader reader = new ClassReader(r.is.get());

				ScanClassVisitor v = new ScanClassVisitor();
				reader.accept(v, 0);
				ch.add(reader, v.cmd);
				Entry e = new Entry();
				e.cr = reader;
				e.name = r.name;
				entries.add(e);
			}
		}

		List<String> all = Arrays.asList("javax/sql/DataSource", "javax/sql/XADataSource");
		List<String> ps = Arrays.asList("java/sql/CallableStatement", "java/sql/PreparedStatement",
				"java/sql/Statement");
		List<String> conn2 = Arrays.asList("javax/sql/XAConnection", "javax/sql/PooledConnection");
		List<String> conn = Arrays.asList("java/sql/Connection");
		List<String> ih = Arrays.asList("java/lang/reflect/InvocationHandler");
		List<String> xa = Arrays.asList("javax/transaction/xa/XAResource");

		LinkedList<String> todo = new LinkedList<>();
		todo.add("java/lang/Object");
		todo.addAll(all);
		todo.addAll(ps);
		todo.addAll(conn);
		todo.addAll(conn2);
		todo.addAll(ih);
		todo.addAll(xa);
		while (!todo.isEmpty()) {
			String t = todo.removeFirst();

			if (ch.classes.containsKey(t))
				continue;

			ClassReader r = new ClassReader(ClassLoader.getSystemClassLoader().getResourceAsStream(t + ".class"));
			ScanClassVisitor v = new ScanClassVisitor();
			r.accept(v, 0);
			ch.addLibrary(r, v.cmd);
			todo.addAll(v.cmd.interfaces);
		}

		Map<ClassMetadata, Map<MethodSignature, String>> allMarks2 = new HashMap<>();

		for (ClassMetadata c : ch.classes.values()) {
			if (c.type != ClassType.CLASS)
				continue;

			for (List<String> l : Arrays.asList(conn2, conn, ps, all, ih, xa)) {

				for (String l1 : l) {
					if (!ch.hasSuperClass(c.name, l1))
						continue;

					Map<MethodSignature, ClassMetadata> toSet = new HashMap<>();
					collectSignatures(ch, ch.get(l1), toSet);

					ClassMetadata c1;

					for (java.util.Map.Entry<MethodSignature, ClassMetadata> e : toSet.entrySet()) {

						String mark = e.getValue().name;

						for (c1 = c; c1 != null; c1 = ch.getSuperClass(c1)) {

							if (!c1.getMethods().stream().map(MethodMetadata::getSignature)
									.anyMatch(e.getKey()::equals))
								continue;

							Map<MethodSignature, String> marks = allMarks2.computeIfAbsent(c1, (x) -> new HashMap<>());

							String old = marks.get(e.getKey());
							if (old != null && !Objects.equals(old, mark)) {
								throw new Error("MARK " + c1 + " " + e.getKey() + " >" + old + "< >" + mark + "<");
							}

							marks.put(e.getKey(), mark);
							mark = "";
							break;
						}

						if (!mark.isEmpty()) {
							System.err.println("not marked " + e);
						}

					}
				}

			}

		}
		for (ClassMetadata c : ch.classes.values()) {
			if (c.type != ClassType.CLASS)
				continue;

			for (String t : all) {
				if (ch.hasSuperClass(c.name, t)) {

					boolean seenObject = false;

					Set<MethodSignature> m = new HashSet<>();

					for (ClassMetadata c1 = c; c1 != null; c1 = ch.getSuperClass(c1)) {
						seenObject |= c1.name.equals("java/lang/Object");
						if (ch.isLibrary(c1)) {
							c1.getMethods().stream().filter(MethodMetadata::isPublic).map(MethodMetadata::getSignature)
									.forEach(m::add);

						}
					}

					if (!seenObject)
						throw new Error();

					for (ClassMetadata c1 = c; c1 != null
							&& !c1.name.equals("java/lang/Object"); c1 = ch.getSuperClass(c1)) {
						if (ch.isLibrary(c1))
							break;

						Map<MethodSignature, String> tt3 = allMarks2.computeIfAbsent(c1, (x) -> new HashMap<>());

						String mark = c1.name;
						c1.getMethods().stream()
								.filter(m1 -> m1.getSignature().name.equals("<init>") || !m.contains(m1.getSignature()))
								.filter(MethodMetadata::isPublic).map(MethodMetadata::getSignature)
								.forEach(p -> tt3.putIfAbsent(p, mark));
					}
				}
			}
		}

		for (Entry e : entries) {
			ClassMetadata cmd = ch.byReader.get(e.cr);
			Map<MethodSignature, String> map = allMarks2.get(cmd);
			if (map != null) {
				ClassWriter cw = new ClassWriter(e.cr,
						0 | ClassReader.EXPAND_FRAMES | ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
					protected String getCommonSuperClass(final String type1, final String type2) {
						Class<?> c, d;

						try {
							c = Class.forName(type1.replace('/', '.'), false, classLoader);
							d = Class.forName(type2.replace('/', '.'), false, classLoader);
						} catch (ClassNotFoundException e) {
							return "java/lang/Object";
							// throw new RuntimeException(e.toString() + " " +
							// type1 + " " + type2);
						}
						if (c.isAssignableFrom(d)) {
							return type1;
						}
						if (d.isAssignableFrom(c)) {
							return type2;
						}
						if (c.isInterface() || d.isInterface()) {
							return "java/lang/Object";
						} else {
							do {
								c = c.getSuperclass();
							} while (!c.isAssignableFrom(d));
							return c.getName().replace('.', '/');
						}
					}
				};

				ClassVisitor returnAdapter;
				returnAdapter = new TracingClassVisitor(cw, map);
				e.cr.accept(returnAdapter, 0 | ClassReader.EXPAND_FRAMES);

				writer.write(e.name, cw.toByteArray());
			} else if (e.name.equals(TracingMethodVisitor.callbackInterface + ".class")) {
				ClassWriter cw = new ClassWriter(e.cr, 0);

				e.cr.accept(cw, 0);

				String impl = "com/github/pms1/jdbctracing/tracers/DefaultTracingCallback";
				{
					FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_STATIC,
							"instance", "L" + TracingMethodVisitor.callbackInterface + ";", null, null);
					fv.visitEnd();
				}
				{
					MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
					mv.visitCode();
					mv.visitTypeInsn(Opcodes.NEW, impl);
					mv.visitInsn(Opcodes.DUP);
					mv.visitMethodInsn(Opcodes.INVOKESPECIAL, impl, "<init>", "()V", false);
					mv.visitFieldInsn(Opcodes.PUTSTATIC, TracingMethodVisitor.callbackInterface, "instance",
							"L" + TracingMethodVisitor.callbackInterface + ";");
					mv.visitInsn(Opcodes.RETURN);
					mv.visitMaxs(2, 0);
					mv.visitEnd();
				}

				byte[] bytes = cw.toByteArray();

				writer.write(TracingMethodVisitor.callbackInterface + ".class", bytes);
			}
		}

		if (false) {
			for (String copy : new String[] { "tracing/Tracer.class", "tracing/Tracer$1.class",
					"tracing/Tracer$2.class", "tracing/DefaultTracingCallback.class" }) {

				try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
					try (InputStream stream = ClassLoader.getSystemClassLoader().getResourceAsStream(copy);) {
						byte[] buf = new byte[8192];
						int read;
						while ((read = stream.read(buf)) != -1) {
							baos.write(buf, 0, read);

						}
					}
					baos.flush();
					writer.write(copy, baos.toByteArray());
				}
			}

			Path p = Paths.get("c:/Users/Mirko/git/jdbc-tracing/jdbc-tracing-api/target/classes");

			try (InputStream stream = Files
					.newInputStream(p.resolve(TracingMethodVisitor.callbackInterface + ".class"))) {
				ClassReader cr = new ClassReader(stream);
				ClassWriter cw = new ClassWriter(cr, 0);

				cr.accept(cw, 0);

				String impl = "tracing/DefaultTracingCallback";
				{
					FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_STATIC,
							"instance", "L" + TracingMethodVisitor.callbackInterface + ";", null, null);
					fv.visitEnd();
				}
				{
					MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
					mv.visitCode();
					mv.visitTypeInsn(Opcodes.NEW, impl);
					mv.visitInsn(Opcodes.DUP);
					mv.visitMethodInsn(Opcodes.INVOKESPECIAL, impl, "<init>", "()V", false);
					mv.visitFieldInsn(Opcodes.PUTSTATIC, TracingMethodVisitor.callbackInterface, "instance",
							"L" + TracingMethodVisitor.callbackInterface + ";");
					mv.visitInsn(Opcodes.RETURN);
					mv.visitMaxs(2, 0);
					mv.visitEnd();
				}

				byte[] bytes = cw.toByteArray();

				ZipEntry ze = new ZipEntry(TracingMethodVisitor.callbackInterface + ".class");
				writer.write(TracingMethodVisitor.callbackInterface + ".class", bytes);

			}
		}

	}

	private static void collectSignatures(ClassHierarchy ch, ClassMetadata classMetadata,
			Map<MethodSignature, ClassMetadata> toSet) {
		if (classMetadata.name.equals("java/lang/Object"))
			throw new Error();

		classMetadata.getMethods().stream().map(MethodMetadata::getSignature)
				.forEach(x -> toSet.putIfAbsent(x, classMetadata));

		if (classMetadata.superName != null && !classMetadata.superName.equals("java/lang/Object"))
			throw new Error(classMetadata.superName);

		for (String i : classMetadata.interfaces) {
			collectSignatures(ch, ch.get(i), toSet);
		}

	}

	public static void run(Path path) {

		Iterable<Resource> files = () -> {

			try {
				return Files.find(path, Integer.MAX_VALUE, (p, b) -> !b.isDirectory()).map(p -> {
					Resource r = new Resource();
					r.name = path.relativize(p).toString().replace('\\', '/');
					r.is = () -> {
						try {
							return Files.newInputStream(p);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					};
					return r;
				}).iterator();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		};

		try {
			ClassLoader classLoader = new URLClassLoader(new URL[] { path.toUri().toURL() });
			process(classLoader, files, (p1, bytes) -> Files.write(path.resolve(p1), bytes));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
