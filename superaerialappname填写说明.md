# superaerialappname.txt 填写说明

SuperHome（超级影视桌面）的预设清单：包名 → 桌面显示名，加上预设文件夹（分组），1.9 起还能写一行置顶名单（`top`）。
本文只讲**这个文件怎么写**；程序内部机制见 [程序说明.md](程序说明.md) 第 3、4、5 节。

> 文件名是 **`superaerialappname.txt`**（上一版误写成 `supeaerialappname.txt`，已改）。电视上放过旧名的，把文件改名即可，旧名不再被读。

以下每一条都是按 [SuperNames.kt](lite/src/main/java/app/supeaerial/tv/lite/SuperNames.kt) 的 `parse()`、[SuperSeed.kt](lite/src/main/java/app/supeaerial/tv/lite/SuperSeed.kt) 的 `place()`、[HomeActivity.kt](lite/src/main/java/app/supeaerial/tv/lite/HomeActivity.kt) 的 `leadPinned()` 实读核对过的，不是推测。

---

## 1. 文件放哪里

程序按顺序找这三个位置，**找到第一个存在的就用它，不会把几处合并**：

| 顺序 | 路径 | 说明 |
|---|---|---|
| 1 | `<外部存储根>/tv/superaerialappname.txt` | 4.x 固件上就是 `/sdcard/tv/`；需要 `READ_EXTERNAL_STORAGE`（4.x 装机时授权，不弹框） |
| 2 | `/sdcard/tv/superaerialappname.txt` | 写死的备用路径，应对 `getExternalStorageDirectory()` 指到别处但 `/sdcard` 仍是真目录的固件 |
| 3 | `<App 自有外部目录>/superaerialappname.txt` | 即 `/sdcard/Android/data/app.supeaerial.tv.lite/files/`，**不需要任何权限**，新固件（6.0+，本 App 没有授权弹框）只能用这条 |

推送命令：

```bash
adb push superaerialappname.txt /sdcard/tv/
adb push superaerialappname.txt /sdcard/Android/data/app.supeaerial.tv.lite/files/
```

还有两份你不用手放：云端下载的一份（存在 App 内部 `files/super/appname.txt`，断网也留着）、打包进 APK 的一份（`lite/src/main/assets/superaerialappname.txt`，对应仓库里的 [site/superaerial/superaerialappname.txt](site/superaerial/superaerialappname.txt)）。

**云端缺省地址是 `https://800915.xyz/superaerial/superaerialappname.txt`**（代码里只有 `SuperNames.CLOUD` 这一处）。这份清单的主副本在仓库的 `site/superaerial/`，但 `tools/deploy-site.sh` 发的是 `aerial.qust.me` 那个 Cloudflare 站点，**不会替你传到 800915.xyz**——那一台的 `/superaerial/` 目录要你自己上传。

> **判断传没传上去别看 HTTP 码**：2026-10-31 实测这个站对任何路径都回 `200` + 一段空壳 HTML（SPA 兜底），不报 404。用 `curl -s https://800915.xyz/superaerial/superaerialappname.txt | head -1` 看**首行是不是 `name `** 才算数。电视收到那种 HTML 会解析出零条，走「这不是清单」的保护、保留已有那份，不会把好数据冲掉，12 小时后再试一次。

### 四层优先级

| 谁说的话最算 | 名字（name） | 分组（group） | 置顶（top） |
|---|---|---|---|
| 1 | 你在 App 里 / 手机页给这个 App 手打的名字 | —（手打的文件夹名不算一层） | —（手打的名字不影响排序） |
| 2 | 电视上这个文件 | 电视上这个文件 | 电视上这个文件 |
| 3 | 云端下载的 | 云端下载的 | 云端下载的 |
| 4 | APK 内置的 | APK 内置的 | APK 内置的 |
| 兜底 | App 自己的安卓标签 | 没有 | 没有 |

**两层的合并方式不一样，这是最容易搞错的地方：**

- **name 按包名逐条覆盖**。同一个包名，高优先级的层有就用它，没有就往下找；四层可以各写不同包名，全部生效。
- **group 按组名整条替换**。同一个组名只要在高优先级的层出现过，这一组就**整条**用它，低优先级层里同组名的包名列表会被完全丢掉——**不是并集**。
  例：云端写 `group 影视 com.a,com.b`，本地写 `group 影视 com.c` → 最终「影视」里只有 `com.c`。
- 同一个文件里写了两行同名 group → **只有第一行生效**（第二行整条丢弃）。一个文件夹的包名必须写在一行里。
- **top 按包名逐条取位置**（1.9 起）。高优先级层写过的包名排在前面，低层只补高层没写过的；所以本地文件那一份只要写了 `top`，它列的顺序就是最终顺序。同一个文件里写几行 `top` 会**接起来**（不像 group 那样丢第二条）。

---

## 2. 通用规则

- **一行一条**。空行忽略。
- **整行注释**：行首（去掉前后空格）是 `#` 或 `//` 的，整行当注释。
- **行内注释会吃进内容**——解析器不剥行尾注释：

  ```
  name com.q 应用名   # 这段会被当成名字的一部分
  ```
  上面这条的名字会变成 `应用名   # 这段会被当成名字的一部分`（中间空格也保留）。**注释永远单独占一行。**
- 字段之间用**空格或 Tab** 分开，连续多个算一个。
- 关键字**大小写不敏感**（`NAME` = `name`）；关键字必须是 `name` / `group` / `top` / `url` 之一，写了别的会被忽略并在 logcat 打一行 `unknown line in the list: …`。
- 编码：**UTF-8 最好**；存成 Windows 记事本默认的 GBK 也读得出（程序先按严格 UTF-8 试，解不动就按 GBK 重解并打一行日志）。带 BOM 会被自动剥掉。
- 字段缺内容的行**静默丢掉**，不报错：`name` 少了显示名、`group` 少了包名、`top` 一个包名都没有，这行就当没写。

---

## 3. `name` 行：改显示名

```
name <包名> <显示名>
```

- 第 2 个字段是包名（Android 的 `applicationId`），**必须和系统里的完全一致，区分大小写**。
- **包名和显示名之间用空格或用逗号都行**：`name com.hgtv.star,红果短剧TV` 和 `name com.hgtv.star 红果短剧TV` 等价。**包名到第一个空格 / Tab / 逗号为止**，从那儿到行尾原样都是显示名（所以名字里既可以有空格，也可以再有逗号：`name com.y 名字,里带逗号` 的显示名就是 `名字,里带逗号`）。
- 从显示名开始到行尾**整段都是名字**，所以名字里可以带空格：

  ```
  name com.cibn.tv        CIBN 酷喵
  name com.xiaodianshi.tv.yst  云视听小电视
  name com.my.app         我的 电视 App 2024
  ```
  上面的显示名分别是 `CIBN 酷喵`、`云视听小电视`、`我的 电视 App 2024`（内部空格原样保留，只去掉首尾）。
- 同一个包名在本文件里写两行，**后一行赢**（前面的被覆盖）。
- 改了显示名不需要重装 App：名字是排版时现取的，重排一次桌面上就变了。

### 怎么查包名

1. **手机页的 JSON（不用数据线，最方便）**。个性化 → 手机页 那一行给出的地址形如 `http://192.168.11.95:8790/ab12cd34/`，把末尾的 `/` 换成 `api/state`，浏览器打开就能看到每个 App 的 `"key"`，那个就是包名。手机页界面上不显示包名，只有这个接口里有。
2. **adb**：`adb shell pm list packages` 全量列；想只列第三方 `pm list packages -3`。不知道是哪个 App 就先在电视上打开它，再 `adb shell dumpsys activity activities | grep -i activity` 看当前界面的包名（4.x 上字段名可能是 `mLastFocusedApp` 一类，翻输出找 `com.xxx/` 的形式）。
3. **logcat**：见第 7 节，改名生效时会打 `pkg(file)` / `pkg(cloud)` / `pkg(apk)`。

---

## 4. `group` 行：预设文件夹（重点）

```
group <组名> <包1>,<包2>,<包3>
```

```
group 影视  com.hgtv.star,com.ktcp.video,com.gitvdemo.video,com.cibn.tv
group 音乐  com.tencent.qqmusictv,com.netease.cloudmusic.tv
group 少儿  com.ktcp.kgold,com.cibn.kids
```

### 组名

- **组名不能带空格**。带了不是报错，是**错位**：解析按「第一个字段=组名，剩下的=包名列表」切。
  `group 我的 影视 com.a,com.b` → 组名变成 `我的`，包名列表变成 `影视 / com.a / com.b`，于是程序去找一个叫「影视」的包（永远匹配不到），实际建出来的文件夹叫「我的」。
- 中文名可以直接用，建议控制在 6 个汉字内。程序**不检查组名长度**（只有手机页手工建文件夹时限 24 字），太长的组名会不会把文件夹托板/标题撑破，需要实机看。
- 组名要和**已有文件夹的名字完全一致**才会并进去（区分大小写、区分空格）。你在电视上手工把「系统应用」改名成「System apps」或加了个空格，就不再是同一个文件夹。

### 包名列表

- **逗号、空格、Tab 三种分隔符可以混用**，都会拆开：
  ```
  group 影视 com.a,com.b com.c	com.d
  ```
  等价于四个包。推荐统一用逗号，看着不容易和组名错位搞混。
- 写了没安装的包名**无害**，程序只是当前找不到对应 App，等它装上再说。
- 一个 App 同时出现在两个 group 里 → 只进**文件里排在前面的那个**组。

### 什么时候真的会被收进去（最关键的边界）

group **不是**「强制布局」，它是**播种**：只处理**程序第一次见到的 App**。

| 情况 | 会不会按 group 收 |
|---|---|
| 全新装的 App（第一次出现在这次开机里） | **会** |
| 这台电视第一次运行 SuperHome，所有 App 都是第一次见 | **会**（全量播种） |
| 之前就在电视上、你已经摆过的 App | **不会**（它在 `seen` 名单里，永远不再动） |
| 你手工挪过位置 / 拖进别的文件夹 / 隐藏过的 App | **不会** |
| 之前被隐藏、后来取消隐藏的 App | **会**（隐藏时不写进 `seen`） |
| ROM 预装、被系统开关关掉后重新打开的 App | 视 `seen` 而定，一般已在名单里 → 不会 |
| 卸载后重装的同名 App | **不会**（`seen` 是累积的，包名还在名单里） |

所以现在这份包名列表已经生效过一轮之后，你**后加 group 想给摆好的 App 归档是不会动的**——只能：
1. 在电视上手工把它拖进同名文件夹（拖进去之后以后也不再被动）；或
2. 清数据重来：`adb shell pm clear app.supeaerial.tv.lite`。代价是**你排好的布局、手打的名字、`seen` 名单全部清空**，等于新装。别顺手敲这条命令。

### 和「系统应用」文件夹的关系

> **1.2 起这个名字从「系统」改成了「系统应用」**（英文 `System` → `System apps`）。老电视上 1.1 建的那个「系统」文件夹**不会再被认出来**：它和里面的 App 都原样留着，但之后新出现的预装 App 会另开一个「系统应用」。要合成一个，就在电视上或手机页把旧的改名成「系统应用」（**一字不差**），或者手工把 App 挪过去。全新安装没有这个问题。

- ROM 预装的 App（`FLAG_SYSTEM`，即卸不掉的）由程序自动收进「系统应用」文件夹，**不用你写 group**。
- 一个 App 既在某个 group 里、又是预装的 → **进 group**（group 判在系统规则前面）。
- 想把非预装的 App 也塞进「系统应用」那个文件夹，可以写 `group 系统应用 com.xxx`——但这个名字是**按设备语言取的**（中文固件 `系统应用`，其他语言 `System apps`，见 `R.string.folder_system`），**语言切了就不是同一个文件夹了**，会出现两个各装一部分 App 的文件夹。要合并用这招时，组名跟着当前语言写。
- 你已经把「系统应用」文件夹改名或删掉的话，下次播种按当前语言的名字**重建一个新的**，只收「第一次见到」的那些。

### 文件夹摆在桌面哪里

- 桌面已经有同名文件夹 → App 收进**那一个**，它原来的位置不动（**已存在的「系统应用」文件夹也不会被挪**，只有首次生成的那个才被放到尾部）。
- 新建的文件夹 → 排到**全部图标的最尾部**；一趟里新建了好几个时，预设组按文件里的先后排在后面，**「系统应用」永远排在所有新建文件夹的最后**。
- 文件夹封面是组内 App 磁贴拼的（最多 9 个，3×3），跟着「App图标类型」的形状走：正方形时托板也是正方形。

---

## 5. `top` 行：把某几个 App 永远排在最前面（1.9 起）

```
top com.hgtv.star,com.mytvb
```

- 第 2 个字段起**全是包名**，逗号、空格、Tab 任意分隔；一行写不完可以分几行写，位置按写下来的先后接起来，同一个包名重复只算第一次。
- **效果**：这几个 App 的磁贴永远排在桌面第一屏最前面，按这行写的顺序；其余的照你手动摆好的顺序，一个都不动。
- **它只改屏幕上看到的，不改存盘的布局**。把某一行 `top` 删掉、或删掉里面某个包名，那块磁贴回到它原来被你挪去的位置。
- **和 `group` 的区别**：`group` 是播种，只管「程序第一次见到的 App」，已经摆过的 App 不动（见第 4 节）；`top` 每次排版都生效，**老电视上装新版本也立刻看得见**。想让某个早就摆好的 App 排到前面，用 `top`，别改 `group`。
- **和 `Defaults.preferred` 的区别**：`preferred` 是 APK 里写死的 16 个包名（本分支最前面加了 `com.hgtv.star`、`com.mytvb`），只在**电视第一次排版**时决定顺序、之后能被你挪走；`top` 是清单里的、不用出包就能改，而且改完马上在前排。两份名单都按**包名**，都不认显示名。
- 置顶的 App 如果已经被收进了某个文件夹，那是**那个文件夹**整块排到前面（文件夹磁贴本身的位置没法拆开排）。
- **能用的地方**：只有桌面。Kids / Elders 模式用它自己的允许名单，手机页的应用列表按名字排，`top` 都不参与。
- **副作用**：置顶的磁贴长按挪位置会「看着没反应」——存盘顺序改了，但屏幕上的顺序仍按 `top` 走。要挪它先从 `top` 里把它去掉。

---

## 6. `url` 行：换云端地址

```
url https://800915.xyz/superaerial/superaerialappname.txt
```

- **只有电视上本地文件里的 `url` 有效**。云端那份或 APK 那份里写 `url` 不会改变下载地址——下载只认「本地文件的 url，没有就用编译时写死的 `SuperNames.CLOUD`」。
- 值必须以 `http://` 或 `https://` 开头，**而且这一行除了 URL 不能再写任何东西**：`url` 取的是行内剩余整段，`url https://x/y.txt  # 注释` 会把 ` # 注释` 一起当地址的一部分，请求必挂。
- 取回来的清单**整份替换**云端层（不增量合并）。所以云端那一份要写成完整清单，别只写增量的几条。
- 拿回来的内容如果**`name`、`group`、`top` 一个都没有**（比如被门户劫持、被 302 到了网站首页），程序判定这不是清单，**保留电视上已有的那份**，只在日志里警告。
- HTTPS 失败会自动降级用 HTTP 再试一次（4.x 上证书经常不认）。所以地址可以是 https，也能在只通 http 的网络上用。
- 频率：12 小时查一次；一次失败后 10 分钟才再试，不会每次开机都打你的服务器。

---

## 7. 改完怎么生效，怎么确认

**读取时机**（本地文件按修改时间判断变没变）：

- 重新启动桌面（开机、重新按 Home 进来、`am force-stop` 后重启）；
- 装上或卸掉任何一个 App；
- 改「App图标类型」或「每排 App」数量；
- 图标库拉到新图片触发重排的时候。

**没有定时器**：不会每分钟去看你的文件。改完文件要让 SuperHome 重读，最省事的是**杀掉进程再进桌面**：

```bash
adb shell am force-stop app.supeaerial.tv.lite
```

（或者在电视上装/卸一个无关 App，也会触发重读。）

**看 logcat**（TAG 统一是 `SuperHome`）：

```bash
adb logcat -c && adb shell am force-stop app.supeaerial.tv.lite && adb logcat -s SuperHome:i SuperHome:W
```

对账用这几行：

| 日志 | 意思 |
|---|---|
| `list: 12 from the TV's file, 6 from the cloud, 6 built in; 2 pinned to the front` | 三层各读到几条 name，末尾是 `top` 合并后有几种包名要置顶（0 = 你这行没生效，多半是关键字或包名拼错） |
| `the list is not UTF-8; reading it as GBK` | 按 GBK 读到了，正常 |
| `unknown line in the list: xxx yyy` | 关键字写错了（该行被忽略） |
| `preset folder «影视» holds 4 app(s): com.a, com.b, …` | 播种动了哪些：只有这里列出的 App 才会被收进文件夹 |
| `6 names taken from the preset list: com.hgtv.star(file), …` | 每个显示名来自哪一层：`typed` 手打 / `file` 电视文件 / `cloud` 云端 / `apk` 内置 / `app` App 自己的标签 |
| `could not fetch the list from …` / `the list at … holds no names or groups; keeping the copy on this TV` | 云端没拉到 / 拉回来的不是清单，两种情况都不会清空你已有的那份 |

**如果 `preset folder` 那行压根没打**，而你确认 group 写对了：多半是这些 App 早就在这台电视上、已经在 `seen` 名单里了——见第 4 节「什么时候真的会被收进去」，这不是 bug，是设计。

---

## 8. 一份能直接抄的完整例子

```
# 我的电视的预设：存成 UTF-8，放 /sdcard/tv/superaerialappname.txt
url https://800915.xyz/superaerial/superaerialappname.txt

# ---- 显示名：包名 + 空格 + 名字（名字里可以带空格，别在这行后面写注释）
name com.hgtv.star              红果短剧TV
name com.ktcp.video             云视听极光
name com.gitvdemo.video         银河奇异果
name com.cibn.tv                CIBN 酷喵
name com.hunantv.license        芒果TV
name com.xiaodianshi.tv.yst     云视听小电视
name com.my.tv.app              我的 电视 App

# ---- 预设文件夹：组名不能带空格；一个组写满一行；只影响第一次见到的 App
group 影视  com.hgtv.star,com.ktcp.video,com.gitvdemo.video,com.cibn.tv,com.hunantv.license
group 音乐  com.tencent.qqmusictv,com.netease.cloudmusic.tv
group 体育  com.miguvideo.tv
group 少儿  com.cibn.kids

# 「系统应用」文件夹不用写：ROM 预装的 App 程序自己收

# ---- 置顶（1.9 起）：这几个永远排在桌面最前面，按这里写的先后；不改你已经摆好的布局
top com.hgtv.star,com.ktcp.video
```

---

## 9. 写错的后果对照

| 现象 | 原因 |
|---|---|
| 名字里莫名其妙多出一段 `# …` 或多余空格 | 行内写了注释；解析器不剥行尾注释 |
| 文件夹建出来了但是空的 / 少了一半 App | 同组名写了两行（只有第一行算），或低优先级层的同组名列表被整条覆盖掉了 |
| 建出来一个名字奇怪的文件夹、里面一个 App 都没有 | 组名里带了空格，后面那段被当成包名 |
| 明明写了 group，摆好的 App 一个都没动 | 那些 App 已经「见过」了，播种只处理新出现的（第 4 节） |
| `url` 写了没用，还是在下载默认地址 | 把 `url` 写进了云端那份文件；只有本地文件的 `url` 有效 |
| 本地放了文件却不生效 | 放到第二个位置但第一个位置已存在同名文件（只取第一个存在的）；或权限被拒（6.0+ 用 `Android/data/.../files/` 那条路径） |
| 装了新 App 它进了「系统应用」而不是你写的组 | 那个 App 是 ROM 预装的且你的 group 里包名拼错了（大小写/少字母），退到系统规则 |
| 写了 `top`，桌面没变化 | logcat 里 `… pinned to the front` 是 0 → 这行没读到（关键字/文件位置不对）；数量对但还是没排前面 → 包名拼错，或那台电视开着 Kids / Elders 模式（它用自己的允许名单） |
| 置顶的磁贴长按挪位置「挪不动」 | 设计如此：`top` 每次上屏都盖在存盘顺序上面。要挪它先把它从 `top` 里去掉 |
| 置顶的 App 找不到了、只有它的文件夹排在前面 | 那个 App 已经被收进文件夹（`group` 或系统规则），置顶的是整个文件夹，不是夹里那一块 |
