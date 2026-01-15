package com.barrage.protocol.datasource;

import com.barrage.protocol.HTTP.HttpTemplate;

public interface DataSource {
    /**
     * 加载数据源。
     * @param param 附加参数（对于文件源是路径，对于控制台源可忽略）
     * @return 准备好的 HttpTemplate 对象
     */
    HttpTemplate load(String param);
}