package com.example.videodownloader.webview2

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.util.Collections
import java.util.IdentityHashMap

/**
 * WebView2 를 C++ 없이 JNA 로 부르기 위한 최소한의 COM 도구.
 * - [ComPtr]: COM 객체 포인터. vtable 의 n 번째 함수를 부른다(순서는 WebView2.h 의 Vtbl 정의).
 * - [ComHandler]: WebView2 가 결과·이벤트를 알려 줄 때 부르는 콜백 객체(IUnknown + Invoke).
 */
internal class ComPtr(val pointer: Pointer) {

    fun call(index: Int, vararg args: Any?): Int {
        val vtable = pointer.getPointer(0)
        val fn = Function.getFunction(vtable.getPointer(index.toLong() * Native.POINTER_SIZE))
        return fn.invokeInt(arrayOf<Any?>(pointer, *args))
    }

    fun check(index: Int, vararg args: Any?) {
        val hr = call(index, *args)
        if (hr < 0) throw IllegalStateException("WebView2 호출 실패(${index}): 0x%08X".format(hr))
    }

    /** 결과를 COM 포인터로 돌려주는 get_ 함수. */
    fun getObject(index: Int, vararg args: Any?): ComPtr? {
        val out = PointerByReference()
        if (call(index, *args, out) < 0) return null
        return out.value?.let(::ComPtr)
    }

    /** LPWSTR 결과(CoTaskMemFree 로 해제해야 함)를 돌려주는 get_ 함수. */
    fun getString(index: Int): String? {
        val out = PointerByReference()
        if (call(index, out) < 0) return null
        val p = out.value ?: return null
        return try {
            p.getWideString(0)
        } finally {
            Ole32.INSTANCE.CoTaskMemFree(p)
        }
    }

    fun getBool(index: Int): Boolean {
        val out = IntByReference()
        call(index, out)
        return out.value != 0
    }

    fun addRef() = call(1)

    fun release() = call(2)

    fun queryInterface(iid: String): ComPtr? {
        val guid = com.sun.jna.platform.win32.Guid.GUID.fromString("{$iid}")
        val out = PointerByReference()
        guid.write()
        val hr = call(0, guid.pointer, out)
        return if (hr < 0) null else out.value?.let(::ComPtr)
    }
}

internal fun wstr(text: String) = WString(text)

/** add_XXX 이벤트 등록 토큰(EventRegistrationToken: int64). */
internal fun eventToken() = LongByReference()

private interface QueryInterfaceFn : Callback {
    fun invoke(self: Pointer, riid: Pointer, out: Pointer?): Int
}

private interface RefCountFn : Callback {
    fun invoke(self: Pointer): Int
}

private interface InvokeFn : Callback {
    fun invoke(self: Pointer, a: Pointer?, b: Pointer?): Int
}

@Structure.FieldOrder("queryInterface", "addRef", "release", "invoke")
internal class HandlerVtbl : Structure() {
    @JvmField var queryInterface: Callback? = null
    @JvmField var addRef: Callback? = null
    @JvmField var release: Callback? = null
    @JvmField var invoke: Callback? = null
}

/**
 * WebView2 의 완료 처리기·이벤트 처리기(ICoreWebView2...Handler) 하나.
 * 모든 처리기는 Invoke(인자 2개) 모양이라 하나의 클래스로 쓴다. 인자가 HRESULT 인 경우는 [hresult] 로 읽는다.
 * 네이티브 쪽이 계속 들고 있으므로 [release] 전까지 GC 되지 않게 [alive] 에 붙잡아 둔다.
 */
internal class ComHandler(private val onInvoke: (a: Pointer?, b: Pointer?) -> Unit) {
    private val vtbl = HandlerVtbl()
    private val obj = Memory(Native.POINTER_SIZE.toLong())

    private val qi = object : QueryInterfaceFn {
        override fun invoke(self: Pointer, riid: Pointer, out: Pointer?): Int {
            out?.setPointer(0, self)
            return 0
        }
    }
    private val ref = object : RefCountFn {
        override fun invoke(self: Pointer): Int = 1
    }
    private val inv = object : InvokeFn {
        override fun invoke(self: Pointer, a: Pointer?, b: Pointer?): Int {
            try {
                onInvoke(a, b)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            return 0
        }
    }

    init {
        vtbl.queryInterface = qi
        vtbl.addRef = ref
        vtbl.release = ref
        vtbl.invoke = inv
        vtbl.write()
        obj.setPointer(0, vtbl.pointer)
        alive.add(this)
    }

    val pointer: Pointer get() = obj

    fun release() {
        alive.remove(this)
    }

    companion object {
        private val alive: MutableSet<ComHandler> = Collections.newSetFromMap(IdentityHashMap())

        /** HRESULT 를 담은 인자(포인터 크기 레지스터의 하위 32비트). */
        fun hresult(p: Pointer?): Int = if (p == null) 0 else Pointer.nativeValue(p).toInt()
    }
}
