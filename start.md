1. 安装 Temurin JDK 21.0.13 到 C:\java\Java\jdk-21。 从清华镜像下载 zip 并核对
     sha256，解压即用。没有安装程序，没有改全局 JAVA_HOME，不影响你其他 Java 8 项目。
  2. 用 JDK 21 编译安装全部模块，再启动应用验证。 验证完已停掉进程并释放端口。

  ┌────────────────────────────────┬──────────────────────────────────────────────────────────┐
  │             验证项             │                           结果                           │
  ├────────────────────────────────┼──────────────────────────────────────────────────────────┤
  │ mvn -version                   │ Java 21.0.13，Eclipse Adoptium                           │
  ├────────────────────────────────┼──────────────────────────────────────────────────────────┤
  │ install，10 个模块             │ BUILD SUCCESS，1 分 04 秒                                │
  ├────────────────────────────────┼──────────────────────────────────────────────────────────┤
  │ spring-boot:run                │ Started in 10.9 s，Tomcat 监听 8080                      │
  ├────────────────────────────────┼──────────────────────────────────────────────────────────┤
  │ HTTP 探活 /jeecg-boot/doc.html │ 200                                                      │
  ├────────────────────────────────┼──────────────────────────────────────────────────────────┤
  │ 收尾                           │ 8080 空闲，无残留 java 进程，全局 JAVA_HOME 仍是 jdk-1.8 │
  └────────────────────────────────┴──────────────────────────────────────────────────────────┘

  以后这样启动

  在 PowerShell 里先把当前会话的 JDK 切到 21，再分两步执行：

  $env:JAVA_HOME = "C:\java\Java\jdk-21"
  $env:Path = "$env:JAVA_HOME\bin;$env:Path"
  cd D:\carsmaga\JeecgBoot\jeecg-boot
  mvn -pl jeecg-module-system\jeecg-system-start -am install
  mvn -pl jeecg-module-system\jeecg-system-start spring-boot:run
  

  # 第一步：编译并安装上游模块到本地仓库。我已经跑过，上游代码有改动时再跑
  mvn -pl jeecg-module-system\jeecg-system-start -am install

  # 第二步：只启动 start 模块，不要带 -am
  mvn -pl jeecg-module-system\jeecg-system-start spring-boot:run

  上游模块现在已经装好，你可以直接跑第二步。若想永久切换，可执行 setx JAVA_HOME
  "C:\java\Java\jdk-21"，但这会影响你本机其他依赖 JDK 8 的项目，是否要改由你决定。VS Code 的 Java 插件同样需要 JDK 17
  以上才能加载这个项目，可在设置里把 java.configuration.runtimes 指到新 JDK。