package com.github.pms1.jdbctracing.api;

public interface TracingCallback {
	/**
	 * Called when a constructor is entered <b>before</b> the next constructor
	 * is called. The next constructor is either a superclass constructor or
	 * another constructor of the same class. As the object is not initialized
	 * at that point, the instance cannot be passed to this method. There will
	 * be an additional call to
	 * {@link #enter(Object[], Object, String, String, String)} after the next
	 * constructor was invoked.
	 */
	void initEnter(Object[] args, String clazz, String method, String signature);

	void initExitException(Throwable e, String clazz, String method, String signature);

	/**
	 * Called when a method is called. For constructors this is called
	 * <b>after</b> the super constructor is called.
	 */
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
	 * Called when a method is ended by an exception. This is called for methods
	 * directly throwing the exception in addition to
	 * {@link #exitThrow(Throwable, Object, String, String, String)}, but also
	 * if the exception is thrown by a transitively called methods.
	 */
	void exitException(Throwable e, Object instance, String clazz, String method, String signature);

}
