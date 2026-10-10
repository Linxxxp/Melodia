# Melodia 车机方控适配指南（MT MCP）

> 基线：v1.12.1（targetSdk36 / Compose / 2 dex / 单 Activity）
> 产出：保留原包名 `com.lin0721.linmusic`；主屏 225% 放大、沉浸全屏、singleTask

## 0. 改动总览

| # | 改动 | 手段 |
|---|---|---|
| 1 | MainActivity `launchMode="singleTask"` | axml |
| 2 | MainActivity `attachBaseContext`：密度×2.25（getDisplay 隔离，缓存幂等） | smali |
| 3 | Theme.Melodia `windowFullscreen=true` | 资源 |
| 4 | onResume + onWindowFocusChanged 双路径隐藏系统栏 | smali |
| 5 | show 包装方法 show→hide | smali |
| 6 | FloatingLyricService `attachBaseContext` 歌词跟UI缩放 + 去黑底 | smali |

> 包名**不改**。不含双屏/悬浮球（1.12.1 AXML 无法加新 Service，createWindowContext 在 Activity 里触发重载）。

## 1. 工作流
1. `mt_apk_open(原包)` → workspaceId；`mt_apk_edit_open` → editSessionId。
2. 每个目标先 `read` 拿 `targetVersion` 再编辑；同类内改方法后类版本会变，重读。
3. `mt_apk_edit_check(runBuildChecks=true)`。
4. `mt_apk_build(sign=true, overwrite=true)`。

## 2. 版本相关项（跨版本必查）
- **MainActivity `.super`**：v1.11.0=`Lzl0`，v1.12.1=`Lrm0`。用 `mt_apk_dex_outline_class` 查。
- **show 包装方法**：v1.11.0=`Lx37;->n`，v1.12.1=`Lpc7;->n`+`Lo3;->s`（两个）。搜 smali `WindowInsetsController;->show(I)V`，全改 `hide`。
- **Theme.Melodia 资源ID**：v1.11.0/v1.12.1 均 `0x7f0f0113`。
- **FloatingLyricService 路径**：`Lcom/lin0721/linmusic/core/player/FloatingLyricService;`（不变）。

## 3. singleTask

MainActivity `<activity>` 标签加 `android:launchMode="singleTask"`。

## 4. 显示大小 225%（attachBaseContext）

**字段**：`# static fields` 区加 `.field public static sBaseDpi:I`（public，FloatingLyricService 跨类读）。

**方法**（`insert_before_match` 锢点 `.method public final m(Landroid/content/Intent;)V`）：

```smali
.method protected attachBaseContext(Landroid/content/Context;)V
    .registers 7
    invoke-super {p0, p1}, Lrm0;->attachBaseContext(Landroid/content/Context;)V
    const/4 v1, 0x0
    :try_start_disp
    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I
    const/16 v2, 0x1e
    if-lt v0, v2, :cond_haveid
    invoke-virtual {p1}, Landroid/content/Context;->getDisplay()Landroid/view/Display;
    move-result-object v0
    if-eqz v0, :cond_haveid
    invoke-virtual {v0}, Landroid/view/Display;->getDisplayId()I
    move-result v1
    :try_end_disp
    .catchall {:try_start_disp .. :try_end_disp} :catch_disp
    :cond_haveid
    :try_start_dens
    invoke-virtual {p1}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
    move-result-object v0
    invoke-virtual {v0}, Landroid/content/res/Resources;->getConfiguration()Landroid/content/res/Configuration;
    move-result-object v2
    sget v3, Lcom/lin0721/linmusic/MainActivity;->sBaseDpi:I
    if-nez v3, :cond_cached
    iget v3, v2, Landroid/content/res/Configuration;->densityDpi:I
    sput v3, Lcom/lin0721/linmusic/MainActivity;->sBaseDpi:I
    :cond_cached
    sget v3, Lcom/lin0721/linmusic/MainActivity;->sBaseDpi:I
    const/4 v4, 0x4
    if-ne v1, v4, :cond_main
    const v4, 0x3feccccd
    goto :cond_mult
    :cond_main
    const v4, 0x40100000
    :cond_mult
    int-to-float v3, v3
    mul-float/2addr v3, v4
    invoke-static {v3}, Ljava/lang/Math;->round(F)I
    move-result v3
    new-instance v4, Landroid/content/res/Configuration;
    invoke-direct {v4, v2}, Landroid/content/res/Configuration;-><init>(Landroid/content/res/Configuration;)V
    iput v3, v4, Landroid/content/res/Configuration;->densityDpi:I
    invoke-virtual {v0}, Landroid/content/res/Resources;->getDisplayMetrics()Landroid/util/DisplayMetrics;
    move-result-object v2
    invoke-virtual {v0, v4, v2}, Landroid/content/res/Resources;->updateConfiguration(Landroid/content/res/Configuration;Landroid/util/DisplayMetrics;)V
    :try_end_dens
    .catchall {:try_start_dens .. :try_end_dens} :catch_dens
    :goto_done
    return-void
    :catch_disp
    move-exception v0
    goto :cond_haveid
    :catch_dens
    move-exception v0
    goto :goto_done
.end method
```

- `Lrm0` = 实际 `.super`（跨版本查）。
- getDisplay 隔离独立 try（主屏基座 context 可能抛异常，隔离后抛了就当 displayId=0→225%）。
- 幂等：`sBaseDpi` 首次读 config.densityDpi 缓存，之后只用缓存×倍率。
- 倍率：主屏 `0x40100000`(2.25f=225%)。per-display 代码保留（displayId==4→185%），但无双屏时不会触发。
- 勿用 `Resources.getSystem()`（首次不放大）；勿每次读当前 densityDpi（会叠乘）。

## 5. 沉浸全屏

### a. Theme.Melodia
`resource_table` 查 Theme.Melodia（例 `0x7f0f0113`），valueXml 加 `<item name="android:windowFullscreen">true</item>`。

### b. onResume 双路径隐藏
`.registers 5`→`9`。`invoke-super onResume` 后插入 getWindow/getDecorView + API≥30 `WindowInsetsController.hide(systemBars())`+`setSystemBarsBehavior(2)` / API<30 `0x1706`。

### c. onWindowFocusChanged 重设
获焦(p1≠0)时重设同一套双路径（插 onResume 前，`.registers 5`）。修冷启动转场/弹窗后沉浸失效。

### d. show→hide
搜 smali `WindowInsetsController;->show(I)V`，找到的包装方法全部 `show`→`hide`。修全屏歌词/弹窗后系统栏被顶出。

## 6. 悬浮歌词跟 UI 缩放 + 去黑底

**FloatingLyricService 加 `attachBaseContext`**（插 onCreate 前，`.registers 6`）：
`invoke-super {p0,p1}, Service;->attachBaseContext`→try：`sget sBaseDpi`×`0x40100000`(2.25f)→round→`p1.getResources()` 拷贝设 densityDpi=target→`updateConfiguration`。catch return。
> 必须在 attachBaseContext（早期），非 onCreate（运行时被 Android15+ 忽略）。

**去黑底**：onCreate 里 `const-string v2, "#B3000000"`→`"#00000000"`。

## 7. 坑
1. ❌ 状态栏占位方案：保持 windowFullscreen + 双路径 + onFocus 重设。
2. ❌ 密度缩放放 Application 不传递；必须放 MainActivity。
3. ✅ `sBaseDpi` 必须 public（FloatingLyricService 跨类读）。
4. ✅ getDisplay 隔离独立 try（否则主屏不放大）。
5. ✅ 缩放幂等：缓存 sBaseDpi；勿用 Resources.getSystem()。
6. ✅ show→hide：搜 `WindowInsetsController;->show`，全改 hide。
7. ✅ 歌词缩放在 attachBaseContext（早期），非 onCreate。
8. ⚠️ manifest AXML：改已有属性可以（如加 singleTask）；加新 element 不行（1.12.1）。
9. ⚠️ 跨版本：`.super` 和 show 包装方法会改名，每次用 §2 的方法重查。
10. ❌ 双屏/悬浮球：1.12.1 无法实现——`createWindowContext` 在 Activity 里触发配置变更→主屏重载；manifest 加不了新 Service 绕开。放弃。

## 8. 交付

输出：`车机_v{版本}.apk`（签名 V1/V2/V3，包名 `com.lin0721.linmusic`）。
验收：manifest 含 `launchMode="singleTask"`、`package=com.lin0721.linmusic`；MainActivity 含 attachBaseContext（getDisplay隔离+sBaseDpi缓存+0x40100000）、onResume 与 onWindowFocusChanged（双路径隐藏）；show 包装方法为 hide；FloatingLyricService 含 attachBaseContext + 背景透明；Theme.Melodia 含 windowFullscreen；`sBaseDpi` 为 public；dex/签名正常。
装机：同签名覆写安装→冷启动→225% 放大、沉浸全屏、singleTask。
