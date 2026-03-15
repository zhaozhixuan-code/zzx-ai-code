# 线上环境进行的操作

1. 注释了pox.xml中的打包检查命令
2. 修改application.yml 中的 local 为 prod
3. 修改AppServiceImpl 中的应用部署目录相关代码

在本地中输入

```bash
java -jar zzx-ai-code-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

测试运行，如果正确即可

在服务器中输入，可以后台运行程序

```bash
nohup java -jar zzx-ai-code-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod > app.log 2>&1 &
```

启动普罗米修斯命令
```bash
prometheus.exe --config.file=E:\javayvan\zzx-ai-code\prometheus.yml
```