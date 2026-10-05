package com.chekayo.feishuantirecall;

import io.github.libxposed.api.XposedModule;

/**
 * 阶段 2 出口验证专用最小 modern 入口：仅被 check-bridge-standalone.sh 编译，不打包。
 * 作用是证明桥接层 5 类（ModuleRuntime/ModuleLog/Reflect/HookRuntime/ModulePath）
 * 能在「只有 android.jar + libxposed classes.jar」的 classpath 下与 XposedModule
 * 子类一起编译通过，且产物零 de.robv 引用。真实入口 FeishuKitModule 在阶段 3 创建。
 */
public final class BridgeSmokeEntry extends XposedModule {
}
