package com.zzx.zzxaicode.generator;

import com.mybatisflex.codegen.Generator;
import com.mybatisflex.codegen.config.GlobalConfig;
import com.zaxxer.hikari.HikariDataSource;

public class MyBatisCodeGenerator {

    // 需要生成的表名
    private static final String[] TABLE_NAMES = {"chat_group_member"};

    public static void main(String[] args) {
        // 1. 配置数据源（硬编码数据库信息，直接替换为你的实际配置）
        HikariDataSource dataSource = new HikariDataSource();
        // 数据库连接URL（硬编码host、port、数据库名）
        dataSource.setJdbcUrl("jdbc:mysql://localhost:3306/zzx_ai_code?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&allowPublicKeyRetrieval=true&useSSL=false");
        // 数据库用户名
        dataSource.setUsername("root");
        // 数据库密码
        dataSource.setPassword("20041123zzx.");
        // 驱动类名（必须配置，避免自动识别失败）
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // 2. 创建全局配置
        GlobalConfig globalConfig = createGlobalConfig();

        // 3. 创建代码生成器并执行生成
        Generator generator = new Generator(dataSource, globalConfig);
        generator.generate();

        // 生成完成后关闭数据源，释放连接
        dataSource.close();
        System.out.println("代码生成完成！生成的表：" + String.join(", ", TABLE_NAMES));
    }

    // 详细配置见：https://mybatis-flex.com/zh/others/codegen.html
    public static GlobalConfig createGlobalConfig() {
        // 创建全局配置对象
        GlobalConfig globalConfig = new GlobalConfig();

        // 设置根包（根据你的项目实际包名调整）
        globalConfig.getPackageConfig()
                .setBasePackage("com.zzx.zzxaicode.genresult");

        // 表策略配置
        globalConfig.getStrategyConfig()
                .setGenerateTable(TABLE_NAMES) // 指定要生成的表
                .setLogicDeleteColumn("isDelete"); // 逻辑删除字段

        // Entity 配置：启用Lombok、指定JDK版本
        globalConfig.enableEntity()
                .setWithLombok(true)
                .setJdkVersion(21);

        // 启用生成 Mapper 接口和 XML 文件
        globalConfig.enableMapper();
        globalConfig.enableMapperXml();

        // 启用生成 Service 接口和实现类
        globalConfig.enableService();
        globalConfig.enableServiceImpl();

        // 启用生成 Controller
        globalConfig.enableController();

        // Javadoc 配置：避免多余的代码改动
        globalConfig.getJavadocConfig()
                .setAuthor("<a href=\"https://github.com/zhaozhixuan-code/\">zhaozhixuan</a>")
                .setSince("");

        return globalConfig;
    }
}