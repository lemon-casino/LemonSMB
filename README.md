# LemonSMB 视觉素材库优化

## 项目概述

本项目是一个视觉素材库管理系统，通过SMB协议连接远程服务器，提供素材的浏览、搜索和下载功能。主要功能包括：

1. 读取远程SMB服务器上的视觉素材库
2. 解析素材库的元数据和文件结构
3. 提供API接口供前端访问素材
4. 使用Redis缓存优化访问性能

## 优化内容

### 1. 缓存机制优化

- 创建了专门的`CacheService`类，封装所有Redis缓存操作
- 实现了文件夹与文件关联关系的缓存
- 缓存文件元数据和文件内容，减少SMB服务器访问
- 设置合理的缓存过期时间

### 2. SMB连接优化

- 实现了SMB连接池管理，避免创建过多连接
- 添加连接错误处理和自动重连机制
- 优化文件读取逻辑，减少连接开销
- 添加连接锁机制，确保线程安全

### 3. 系统启动优化

- 创建`CacheInitializer`类，系统启动时预加载常用数据
- 优化预加载过程，避免服务器过载
- 添加进度日志，方便监控加载过程

### 4. API接口优化

- 优化`/files`接口，优先从缓存获取数据
- 优化`/folder-files`接口，支持从缓存获取文件夹内容
- 保留原有API接口，确保兼容性

## 技术栈

- Spring Boot
- Redis缓存
- SMBJ (SMB Java客户端)
- Jackson JSON处理

## 使用说明

### API接口

- `/metadata` - 获取素材库元数据
- `/files?path={id}&offset=0&limit=100` - 获取指定文件夹的文件列表
- `/folder-files?id={id}` - 根据文件夹ID获取文件列表
- `/image?id={id}&thumbnail=true` - 获取图片资源
- `/preview?id={id}` - 预览文件内容
- `/download?id={id}` - 下载文件

### 缓存键说明

- `metadata` - 主元数据JSON
- `meta:{fileId}` - 文件元数据
- `folder:{folderId}` - 文件夹包含的文件ID列表
- `file:{fileId}` - 文件内容
- `image:{fileId}:{thumbnail}` - 图片内容

## 注意事项

1. 系统启动时会预加载数据，可能需要一段时间
2. 如需清除缓存，可以使用Redis命令`FLUSHDB`
3. 修改SMB服务器配置后需要重启应用 