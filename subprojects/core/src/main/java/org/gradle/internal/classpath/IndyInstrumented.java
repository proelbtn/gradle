/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.classpath;

import org.codehaus.groovy.vmplugin.v8.CacheableCallSite;
import org.codehaus.groovy.vmplugin.v8.IndyInterface;
import org.gradle.api.GradleException;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class IndyInstrumented {

    private static final Map<String, MethodInterceptor> INTERCEPTORS = new HashMap<>();
    static {
        INTERCEPTORS.put("getProperty", new SystemGetPropertyInterceptor());
        INTERCEPTORS.put("setProperties", new SystemSetPropertiesInterceptor());
    }
    /**
     * bootstrap method for method calls from Groovy compiled code with indy
     * enabled. This method gets a flags parameter which uses the following
     * encoding:<ul>
     * <li>{@value IndyInterface#SAFE_NAVIGATION} is the flag value for safe navigation see {@link IndyInterface#SAFE_NAVIGATION}</li>
     * <li>{@value IndyInterface#THIS_CALL} is the flag value for a call on this see {@link IndyInterface#THIS_CALL}</li>
     * </ul>
     *
     * @param caller - the caller
     * @param callType - the type of the call
     * @param type - the call site type
     * @param name - the real method name
     * @param flags - call flags
     * @return the produced CallSite
     * @since Groovy 2.1.0
     */
    public static CallSite bootstrap(MethodHandles.Lookup caller, String callType, MethodType type, String name, int flags) {
        CacheableCallSite cs = toGroovyCacheableCallSite(IndyInterface.bootstrap(caller, callType, type, name, flags));
        if (callType.equals("invoke")) {
            MethodInterceptor interceptor = INTERCEPTORS.get(name);
            if (interceptor != null) {
                MethodHandle defaultTarget = interceptor.decorate(cs.getDefaultTarget(), caller, flags);
                cs.setTarget(defaultTarget);
                cs.setDefaultTarget(defaultTarget);
                cs.setFallbackTarget(interceptor.decorate(cs.getFallbackTarget(), caller, flags));
            }
        }
        return cs;
    }

    private static CacheableCallSite toGroovyCacheableCallSite(CallSite cs) {
        if (!(cs instanceof CacheableCallSite)) {
            throw new GradleException("Groovy produced unrecognized callsite type of " + cs.getClass());
        }
        return (CacheableCallSite) cs;
    }

    private static class Call {
        private final MethodHandle original;
        private final Object[] args;
        private final boolean isSpread;

        public Call(MethodHandle original, Object[] args, boolean isSpread) {
            this.original = original;
            this.args = args;
            this.isSpread = isSpread;
        }

        public Object callOriginal() throws Throwable {
            return original.invokeExact(args);
        }

        public Object getReceiver() {
            return args[0];
        }

        public Object[] getArguments() {
            if (isSpread) {
                Object[] argsArray = (Object[]) args[1];
                return unwrap(argsArray, 0, argsArray.length);
            }
            return unwrap(args, 1, args.length - 1);
        }

        private static Object[] unwrap(Object[] array, int offset, int size) {
            Object[] result = new Object[size];
            for (int i = 0; i < size; ++i) {
                result[i] = Instrumented.unwrap(array[i + offset]);
            }
            return result;
        }
    }


    private static abstract class MethodInterceptor {
        private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

        private static final MethodHandle INTERCEPTOR;

        static {
            try {
                INTERCEPTOR = LOOKUP.findVirtual(MethodInterceptor.class, "intercept", MethodType.methodType(Object.class, MethodHandle.class, int.class, String.class, Object[].class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new GradleException("Failed to set up an interceptor method", e);
            }
        }

        public MethodHandle decorate(MethodHandle original, MethodHandles.Lookup caller, int flags) {
            MethodHandle spreader = original.asSpreader(Object[].class, original.type().parameterCount());
            MethodHandle decorated = MethodHandles.insertArguments(INTERCEPTOR, 0, this, spreader, flags, caller.lookupClass().getName());
            return decorated.asCollector(Object[].class, original.type().parameterCount()).asType(original.type());
        }

        private Object intercept(MethodHandle original, int flags, String consumer, Object[] args) throws Throwable {
            boolean isSpread = (flags & IndyInterface.SPREAD_CALL) != 0;
            return doIntercept(consumer, new Call(original, args, isSpread));
        }

        public abstract Object doIntercept(String consumer, Call call) throws Throwable;
    }


    private static class SystemGetPropertyInterceptor extends MethodInterceptor {
        @Override
        public Object doIntercept(String consumer, Call call) throws Throwable {
            if (!System.class.equals(call.getReceiver())) {
                return call.callOriginal();
            }
            Object[] args = call.getArguments();
            switch (args.length) {
                case 1:
                    return Instrumented.systemProperty(Instrumented.convertToString(args[0]), consumer);
                case 2:
                    return Instrumented.systemProperty(Instrumented.convertToString(args[0]), Instrumented.convertToString(args[1]), consumer);
            }
            return call.callOriginal();
        }
    }

    private static class SystemSetPropertiesInterceptor extends MethodInterceptor {
        @Override
        public Object doIntercept(String consumer, Call call) throws Throwable {
            if (!System.class.equals(call.getReceiver())) {
                return call.callOriginal();
            }
            Object[] args = call.getArguments();
            switch (args.length) {
                case 1:
                    Instrumented.setSystemProperties((Properties) args[0], consumer);
                    return null;
            }
            return call.callOriginal();
        }
    }
}
