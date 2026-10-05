# vendored libxposed API

构建链（build.ps1 / build.sh）的编译期依赖，手动 vendored，不走包管理器。

```text
artifact: io.github.libxposed:api:102.0.0
type: aar
sha256: 423484a6e1807e7a423c4b88fcd8176d104318259d91791877fed88fe91479d0
source: https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar
```

用法：构建脚本从 AAR 解出 `classes.jar` 加入 javac classpath，**仅用于编译**，
不进入最终模块 APK 的 DEX（d8 输入不含它）。

替换二进制时必须同步更新上面的 sha256，并重新走一遍构建验证。
