package com.github.pms1.jdbctracing.api.core;

import java.util.Map;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.JSRInlinerAdapter;

import com.github.pms1.jdbctracing.api.core.InstumentationCore.MethodSignature;

/**
 * A {@link ClassVisitor} that instruments all methods with
 * {@link TracingMethodVisitor}.
 * 
 * @author pms1
 */
public class TracingClassVisitor extends ClassVisitor {
	private String className;
	private Map<MethodSignature, String> sigs;

	public TracingClassVisitor(ClassVisitor cv, Map<MethodSignature, String> sigs) {
		super(Opcodes.ASM5, cv);
		this.sigs = sigs;
	}

	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		super.visit(version, access, name, signature, superName, interfaces);
		this.className = name;
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		MethodVisitor mv;
		mv = cv.visitMethod(access, name, desc, signature, exceptions);
		mv = new JSRInlinerAdapter(mv, access, name, desc, signature, exceptions);

		String mark = sigs.get(new MethodSignature(name, desc));
		if (mark != null)
			mv = new TracingMethodVisitor(Opcodes.ASM5, mark, access, name, desc, mv);

		return mv;
	}
}
