package com.github.pms1.jdbctracing.tracers;

import com.github.pms1.jdbctracing.api.FilterTracingCallback;

public class DefaultTracingCallback extends FilterTracingCallback {

	public DefaultTracingCallback() {
		super(new PrintTracingCallback());
	}

}
