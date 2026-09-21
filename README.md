# 无障碍步行导航

TalkBack / TTS 优先的 Android 步行导航。高德只负责底图、算路和 GPS 绑路，引导逻辑在本仓库自己实现。

## 本地配置（不要提交）

仓库里没有高德 Key 和签名文件。克隆后：

1. 复制 `local.properties.example` 为 `local.properties`
2. 填写本机 `sdk.dir`
3. 填写高德开放平台 Android Key（包名 `com.hu.nav`）
4. 把签名文件放到项目根目录（默认文件名 `navi.jks`），并填写：
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`

`local.properties`、`*.jks`、`*.keystore` 已在 `.gitignore` 中忽略。

## 构建

```bash
./gradlew :app:assembleDebug
```

Windows：

```bat
gradlew.bat :app:assembleDebug
```
