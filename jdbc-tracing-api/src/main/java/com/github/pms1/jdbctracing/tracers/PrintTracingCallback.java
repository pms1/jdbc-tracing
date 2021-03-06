package com.github.pms1.jdbctracing.tracers;

import com.github.pms1.jdbctracing.api.TracingCallback;

public class PrintTracingCallback implements TracingCallback {

	private static String id(Object o) {
		if (o == null) {
			return null;
		} else if (o instanceof String || o instanceof Integer || o instanceof Long || o instanceof Double
				|| o instanceof Float) {
			return o.toString();
		} else if (o instanceof Object[]) {
			StringBuilder sb = new StringBuilder();
			String prefix = "";
			sb.append("[");
			for (Object o1 : (Object[]) o) {
				sb.append(prefix).append(id(o1));
				prefix = ", ";
			}
			sb.append("]");
			return sb.toString();
		} else {
			return o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o));
		}
	}

	@Override
	public void enter(Object[] args, Object instance, String clazz, String method, String signature) {
		System.out.print("ENTER " + clazz + " " + method + " " + signature + " " + id(instance) + " ");
		if (args != null)
			for (Object a : args)
				System.out.print(" " + id(a));
		System.out.println();
	}

	@Override
	public void exitReturn(Object result, Object instance, String clazz, String method, String signature) {
		System.out.println("RETURN " + clazz + " " + method + " " + signature + " " + id(instance) + " " + id(result));
	}

	@Override
	public void exitReturn(Object instance, String clazz, String method, String signature) {
		System.out.println("RETURN " + clazz + " " + method + " " + signature + " " + id(instance));
	}

	@Override
	public void exitException(Throwable e, Object instance, String clazz, String method, String signature) {
		System.out.println("EXCEPTION " + clazz + " " + method + " " + signature + " " + id(instance) + " " + id(e));
	}

	@Override
	public void initEnter(Object[] args, String clazz, String method, String signature) {
		System.out.print("ENTER-INIT " + clazz + " " + method + " " + signature + " ");
		if (args != null)
			for (Object a : args)
				System.out.print(" " + id(a));
		System.out.println();
	}

	@Override
	public void initExitException(Throwable t, String clazz, String method, String signature) {
		System.out.print("EXCEPTION-INIT " + clazz + " " + method + " " + signature + " ");
		System.out.println();
	}
}
