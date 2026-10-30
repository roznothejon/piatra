// Can this CPU run the Cactus engine? It needs two ARM instruction sets:
// half-precision float maths (ASIMDHP) and dot products (ASIMDDP).
// The kernel lists the CPU's features as bits in "HWCAP"; getauxval reads them.
#include <jni.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>

// JNI finds this function by its name: Java_<package>_<class>_<method>,
// i.e. com.cactus.CactusCpuJNI.nativeIsCactusSupported() in Cactus.kt.
JNIEXPORT jboolean JNICALL
Java_com_cactus_CactusCpuJNI_nativeIsCactusSupported(JNIEnv *env, jclass clazz) {
    const unsigned long hwcap = getauxval(AT_HWCAP);
    const unsigned long required = HWCAP_ASIMDHP | HWCAP_ASIMDDP;
    return (hwcap & required) == required ? JNI_TRUE : JNI_FALSE;
}
