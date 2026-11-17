package com.pocketai.acceptance;

import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;

/**
 * External host acceptance runner. Android services remain in Robolectric's
 * sandbox while each JNI bridge and its native library have one parent loader.
 * No production classes, model paths, inference results or guards are replaced.
 */
public final class HostNativeRobolectricRunner extends RobolectricTestRunner {
    public HostNativeRobolectricRunner(Class<?> testClass) throws InitializationError {
        super(testClass);
    }

    @Override
    protected InstrumentationConfiguration createClassLoaderConfig(FrameworkMethod method) {
        return new InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotAcquirePackage("ai.onnxruntime.")
            .doNotAcquireClass("com.pocketai.engine.NativeTokenizerBridge")
            .doNotAcquireClass("com.pocketai.engine.NativeBridge")
            .build();
    }
}
