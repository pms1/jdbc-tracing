package com.github.pms1.jdbctracing.api;

public interface TracingCallback {
	void enter(Object[] args, Object instance, String clazz, String method, String signature);

	/**
	 * Called for non void methods before they are ended with a {@code return}
	 * statement.
	 */
	void exitReturn(Object result, Object instance, String clazz, String method, String signature);

	/**
	 * Called for void methods before they are ended with a {@code return}
	 * statement.
	 */
	void exitReturn(Object instance, String clazz, String method, String signature);

	/**
	 * Called when a method is ended by a {@code throw} statement. The
	 * {@link #exitException(Throwable, Object, String, String, String)} will be
	 * called additionally.
	 */
	void exitThrow(Throwable e, Object instance, String clazz, String method, String signature);

	/**
	 * Called when a method is ended by an exception. This is called for methods
	 * directly throwing the exception in addition to
	 * {@link #exitThrow(Throwable, Object, String, String, String)}, but also
	 * if the exception is thrown by a transitively called methods.
	 */
	void exitException(Throwable e, Object instance, String clazz, String method, String signature);

}
