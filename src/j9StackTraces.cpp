/*
 * Copyright 2021 Andrei Pangin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <stdlib.h>
#include <sys/time.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include "j9StackTraces.h"
#include "profiler.h"
#include "perfEvents.h"


struct SignalledThread {
    JNIEnv* env;
    void* pc;
    u64 counter;
};

struct jvmtiFrameInfoExtended {
    jmethodID method;
    jlocation location;
    jlocation machinepc;
    jint type;
    void* native_frame_address;
};

struct jvmtiStackInfoExtended {
    jthread thread;
    jint state;
    jvmtiFrameInfoExtended* frame_buffer;
    jint frame_count;
};

enum {
    SHOW_COMPILED_FRAMES = 4,
    SHOW_INLINED_FRAMES = 8
};


enum {
    J9_STOPPED = 0x40,
    J9_HALT_THREAD_INSPECTION = 0x8000
};

class J9VMThread {
  private:
    uintptr_t _unused1[10];
    uintptr_t _overflow_mark;
    uintptr_t _unused2[8];
    uintptr_t _flags;

  public:
    uintptr_t getAndSetFlag(uintptr_t flag) {
        return __sync_fetch_and_or(&_flags, flag);
    }

    void clearFlag(uintptr_t flag) {
        __sync_fetch_and_and(&_flags, ~flag);
    }

    void setOverflowMark() {
        __atomic_store_n(&_overflow_mark, (uintptr_t)-1, __ATOMIC_RELEASE);
    }
};


static JNIEnv* _self_env = NULL;

static SignalledThread* findThread(jthread thread, SignalledThread* array, size_t length) {
    JNIEnv* vm_thread = VM::getJ9vmThread(thread);
    if (vm_thread != NULL) {
        for (size_t i = 0; i < length; i++) {
            if (array[i].env == vm_thread) {
                return &array[i];
            }
        }
    }
    return NULL;
}


Error J9StackTraces::start(Arguments& args) {
    _max_stack_depth = args._jstackdepth; 
    _cstack = args._cstack;

    if (pipe(_pipe) != 0) {
        return Error("Failed to create pipe");
    }
    fcntl(_pipe[1], F_SETFL, O_NONBLOCK);

    if (pthread_create(&_thread, NULL, threadEntry, this) != 0) {
        close(_pipe[0]);
        close(_pipe[1]);
        return Error("Unable to create sampler thread");
    }

    return Error::OK;
}

void J9StackTraces::stop() {
    if (_thread != 0) {
        close(_pipe[1]);
        pthread_join(_thread, NULL);
        close(_pipe[0]);
        _thread = 0;
    }
}

void J9StackTraces::timerLoop() {
    JNIEnv* jni = VM::attachThread("Async-profiler Sampler");
    __atomic_store_n(&_self_env, jni, __ATOMIC_RELEASE);

    jvmtiEnv* jvmti = VM::jvmti();
    SignalledThread signalled_threads[256];

    int max_frames = _max_stack_depth + MAX_NATIVE_FRAMES + RESERVED_FRAMES;
    ASGCT_CallFrame* frames = (ASGCT_CallFrame*)malloc(max_frames * sizeof(ASGCT_CallFrame));
    jvmtiFrameInfoExtended* jvmti_frames = (jvmtiFrameInfoExtended*)malloc(max_frames * sizeof(jvmtiFrameInfoExtended));

    while (true) {
        ssize_t bytes = read(_pipe[0], signalled_threads, sizeof(signalled_threads));
        if (bytes <= 0) {
            if (bytes < 0 && errno == EAGAIN) {
                continue;
            }
            break;
        }
        size_t signalled_thread_count = bytes / sizeof(SignalledThread);

        jni->PushLocalFrame(64);

        jint thread_count;
        jthread* threads;
        if (jvmti->GetAllThreads(&thread_count, &threads) != 0) {
            break;
        }

        for (int i = 0; i < thread_count; i++) {
            SignalledThread* st = findThread(threads[i], signalled_threads, signalled_thread_count);
            if (st == NULL) {
                continue;
            }

            int tid = VM::getOSThreadID(threads[i]);
            int num_frames = 0;
            if (_cstack != CSTACK_NO) {
                if (st->pc != NULL) {
                    jmethodID method_id = (jmethodID)Profiler::instance()->findNativeMethod(st->pc);
                    if (method_id != NULL) {
                        frames[num_frames].bci = BCI_NATIVE_FRAME;
                        frames[num_frames].method_id = method_id;
                        num_frames++;
                    }
                } else {
                    num_frames = Profiler::instance()->getNativeTrace(frames, tid);
                }
            }
            PerfEvents::resetForThread(tid);

            jint num_jvmti_frames;
            if (VM::_getStackTraceExtended(jvmti, SHOW_COMPILED_FRAMES | SHOW_INLINED_FRAMES, threads[i],
                                           0, _max_stack_depth, jvmti_frames, &num_jvmti_frames) == 0) {
                for (int j = 0; j < num_jvmti_frames; j++) {
                    frames[num_frames].method_id = jvmti_frames[j].method;
                    frames[num_frames].bci = (jvmti_frames[j].type << 24) | jvmti_frames[j].location;
                    num_frames++;
                }
                Profiler::instance()->recordExternalSample(st->counter, tid, num_frames, frames);
            }
        }

        jvmti->Deallocate((unsigned char*)threads);
        jni->PopLocalFrame(NULL);
    }

    free(jvmti_frames);
    free(frames);

    __atomic_store_n(&_self_env, NULL, __ATOMIC_RELEASE);
    VM::detachThread();
}

void J9StackTraces::checkpoint(void* pc, u64 counter) {
    JNIEnv* self_env = __atomic_load_n(&_self_env, __ATOMIC_ACQUIRE);
    if (self_env == NULL) {
        // Sampler thread is not ready
        return;
    }

    JNIEnv* env = VM::jni();
    if (env != NULL && env != self_env) {
        J9VMThread* vm_thread = (J9VMThread*)env;
        uintptr_t flags = vm_thread->getAndSetFlag(J9_HALT_THREAD_INSPECTION);
        if (flags & J9_HALT_THREAD_INSPECTION) {
            // Thread is already scheduled for inspection, no need to notify again
            return;
        } else if (!(flags & J9_STOPPED)) {
            vm_thread->setOverflowMark();
            SignalledThread st = {env, pc, counter};
            if (write(_pipe[1], &st, sizeof(st)) > 0) {
                return;
            }
        }
        // Something went wrong - rollback
        vm_thread->clearFlag(J9_HALT_THREAD_INSPECTION);
    }
}