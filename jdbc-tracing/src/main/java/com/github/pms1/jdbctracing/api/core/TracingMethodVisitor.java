package com.github.pms1.jdbctracing.api.core;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * A {@link MethodVisitor} that instruments code for tracing
 * 
 * @see https://gist.github.com/VijayKrishna/1ca807c952187a7d8c4d
 *
 */
public class TracingMethodVisitor extends AdviceAdapter {
	private final Label startSuper = new Label();
	private final Label startBody = new Label();

	private final String owner;
	private final String name;
	private final int access;

	public TracingMethodVisitor(int api, String owner, int access, String name, String desc, MethodVisitor mv) {
		super(Opcodes.ASM5, mv, access, name, desc);
		this.owner = owner;
		this.name = name;
		this.access = access;
	}

	public static final String callbackInterface = "com/github/pms1/jdbctracing/api/TracingCallback";

	@Override
	public void visitCode() {
		if (name.equals("<init>")) {
			loadCallback();
			loadArgArray();
			mv.visitLdcInsn(owner);
			mv.visitLdcInsn(name);
			mv.visitLdcInsn(methodDesc);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, callbackInterface, "initEnter",
					"([Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", true);
		}
		visitLabel(startSuper);
		super.visitCode();
	}

	private void invokeTracer(String method, String firstArg) {
		if ((access & Opcodes.ACC_STATIC) != 0)
			mv.visitInsn(ACONST_NULL);
		else
			loadThis();

		mv.visitLdcInsn(owner);
		mv.visitLdcInsn(name);
		mv.visitLdcInsn(methodDesc);

		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, callbackInterface, method,
				"(" + firstArg + "Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", true);

	}

	private void loadCallback() {
		mv.visitFieldInsn(Opcodes.GETSTATIC, callbackInterface, "instance", "L" + callbackInterface + ";");
	}

	@Override
	protected void onMethodEnter() {
		loadCallback();
		loadArgArray();
		invokeTracer("enter", "[Ljava/lang/Object;");
		visitLabel(startBody);
	}

	public void visitMaxs(int maxStack, int maxLocals) {
		Label endBody = new Label();
		mv.visitLabel(endBody);

		mv.visitTryCatchBlock(startBody, endBody, endBody, "java/lang/Throwable");
		dup();
		loadCallback();
		swap();
		invokeTracer("exitException", "Ljava/lang/Throwable;");
		mv.visitInsn(ATHROW);

		if (name.equals("<init>")) {
			Label superExceptionHandler = new Label();
			mv.visitLabel(superExceptionHandler);
			mv.visitTryCatchBlock(startSuper, startBody, superExceptionHandler, "java/lang/Throwable");
			dup();
			loadCallback();
			swap();
			mv.visitLdcInsn(owner);
			mv.visitLdcInsn(name);
			mv.visitLdcInsn(methodDesc);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, callbackInterface, "initExitException",
					"(Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", true);
			mv.visitInsn(ATHROW);
		}

		mv.visitMaxs(maxStack, maxLocals);
	}

	@Override
	protected void onMethodExit(int opcode) {
		String method;
		String firstArg;

		if (opcode == ATHROW)
			return;

		if (opcode == RETURN) {
			loadCallback();
			method = "exitReturn";
			firstArg = "";
		} else if (opcode == ARETURN) {
			dup();
			loadCallback();
			swap();
			method = "exitReturn";
			firstArg = "Ljava/lang/Object;";
		} else {
			if (opcode == LRETURN || opcode == DRETURN) {
				dup2();
				loadCallback();
				dupX2();
				pop();
			} else {
				dup();
				loadCallback();
				swap();
			}
			box(Type.getReturnType(this.methodDesc));
			method = "exitReturn";
			firstArg = "Ljava/lang/Object;";
		}

		invokeTracer(method, firstArg);

	}
}