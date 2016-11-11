package com.github.pms1.jdbctracing.api;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.PooledConnection;
import javax.sql.XAConnection;

public class FilterTracingCallback implements TracingCallback {
	private final boolean debug = false;

	private final TracingCallback next;

	public FilterTracingCallback(TracingCallback next) {
		Objects.requireNonNull(next);
		this.next = next;
	}

	private static ThreadLocal<AtomicInteger> t = new ThreadLocal<AtomicInteger>() {
		@Override
		protected AtomicInteger initialValue() {
			return new AtomicInteger();
		}
	};

	private class Target {
		private final Object instance;
		private final String clazz;
		private final String method;
		private final String signature;

		Target(Object instance, String clazz, String method, String signature) {
			this.instance = instance;
			this.clazz = clazz;
			this.method = method;
			this.signature = signature;
		}
	}

	private static ThreadLocal<LinkedList<Target>> s = new ThreadLocal<LinkedList<Target>>() {
		@Override
		protected LinkedList<Target> initialValue() {
			return new LinkedList<Target>();
		}
	};

	private static boolean isInvoke(Object instance, String method, String signature) {
		return instance instanceof InvocationHandler && method.equals("invoke") && signature
				.equals("(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;");
	}

	@Override
	public void enter(Object[] args, Object instance, String clazz, String method, String signature) {

		int level = t.get().getAndIncrement();

		if (debug)
			System.err.println("RAW ENTER " + level + " " + clazz + " " + method + " " + signature);

		if (level == 0) {
			if (isInvoke(instance, method, signature)) {
				if (debug)
					System.err.println("RAW INVOKE " + args[1]);

				Method m = (Method) args[1];
				Class<?> c = m.getDeclaringClass();
				if (c == Connection.class) {

				} else if (c == XAConnection.class || c == PooledConnection.class) {
					if (args[0] instanceof XAConnection)
						c = XAConnection.class;
					else if (args[0] instanceof PooledConnection)
						c = PooledConnection.class;
					else
						throw new Error();
				} else if (c == PreparedStatement.class || c == CallableStatement.class || c == Statement.class) {
					if (args[0] instanceof CallableStatement)
						c = CallableStatement.class;
					else if (args[0] instanceof PreparedStatement)
						c = PreparedStatement.class;
					else if (args[0] instanceof Statement)
						c = Statement.class;
					else
						throw new Error();
				} else if (c == Object.class) {
					s.get().addLast(null);
					return;
				} else {
					throw new Error("c=" + c);
				}

				Method m1 = m;
				for (;;) {
					try {
						m1 = c.getMethod(m.getName(), m.getParameterTypes());
						break;
					} catch (ReflectiveOperationException e) {
						c = c.getSuperclass();
					}
				}

				instance = args[0];
				clazz = m1.getDeclaringClass().getName();
				method = m1.getName();
				signature = createSignature(m1);
				args = (Object[]) args[2];

				s.get().addLast(new Target(instance, clazz, method, signature));
			}

			next.enter(args, instance, clazz, method, signature);
		}

	}

	private static void append(StringBuilder sb, Class<?> c) {
		if (c == void.class)
			sb.append("V");
		else if (c == int.class)
			sb.append("I");
		else if (c == long.class)
			sb.append("J");
		else if (c == double.class)
			sb.append("D");
		else if (c == float.class)
			sb.append("F");
		else if (c == boolean.class)
			sb.append("Z");
		else if (c.isPrimitive())
			throw new Error();
		else if (c.isArray()) {
			sb.append("[");
			append(sb, c.getComponentType());
		} else
			sb.append("L" + c.getCanonicalName().replace('.', '/') + ";");
	}

	private static String createSignature(Method m) {
		StringBuilder sb = new StringBuilder();
		sb.append("(");
		for (Class<?> c : m.getParameterTypes())
			append(sb, c);
		sb.append(")");
		append(sb, m.getReturnType());
		return sb.toString();
	}

	@Override
	public void exitReturn(Object result, Object instance, String clazz, String method, String signature) {
		int level = t.get().decrementAndGet();
		if (debug)
			System.err.println("RAW EXIT-R-R " + level + " " + clazz + " " + method + " " + signature);
		if (level == 0) {
			if (isInvoke(instance, method, signature)) {
				Target pop = s.get().removeLast();
				if (pop == null)
					return;
				instance = pop.instance;
				clazz = pop.clazz;
				method = pop.method;
				signature = pop.signature;

				// if the called method returns void, we have to call the
				// exitReturn method without the
				// result parameter
				if (signature.endsWith("V")) {
					next.exitReturn(instance, clazz, method, signature);
					return;
				}
			}
			next.exitReturn(result, instance, clazz, method, signature);
		}
	}

	@Override
	public void exitReturn(Object instance, String clazz, String method, String signature) {
		int level = t.get().decrementAndGet();
		if (debug)
			System.err.println("RAW EXIT-R-V " + level + " " + clazz + " " + method + " " + signature);
		if (level == 0) {
			if (isInvoke(instance, method, signature)) {
				Target pop = s.get().removeLast();
				if (pop == null)
					return;
				instance = pop.instance;
				clazz = pop.clazz;
				method = pop.method;
				signature = pop.signature;
			}
			next.exitReturn(instance, clazz, method, signature);
		}
	}

	@Override
	public void exitException(Throwable e, Object instance, String clazz, String method, String signature) {
		int level = t.get().decrementAndGet();
		if (debug)
			System.err.println("RAW EXIT-E " + level + " " + clazz + " " + method + " " + signature);
		if (level == 0) {
			if (isInvoke(instance, method, signature)) {
				Target pop = s.get().removeLast();
				if (pop == null)
					return;
				instance = pop.instance;
				clazz = pop.clazz;
				method = pop.method;
				signature = pop.signature;
			}
			next.exitException(e, instance, clazz, method, signature);
		}
	}

	@Override
	public void exitThrow(Throwable e, Object instance, String clazz, String method, String signature) {
		int level = t.get().get();
		if (debug)
			System.err.println("RAW EXIT-T " + level + " " + clazz + " " + method + " " + signature);
		if (level == 1) {
			if (isInvoke(instance, method, signature)) {
				Target pop = s.get().getLast();
				if (pop == null)
					return;
				instance = pop.instance;
				clazz = pop.clazz;
				method = pop.method;
				signature = pop.signature;
			}
			next.exitThrow(e, instance, clazz, method, signature);
		}

	}
}
