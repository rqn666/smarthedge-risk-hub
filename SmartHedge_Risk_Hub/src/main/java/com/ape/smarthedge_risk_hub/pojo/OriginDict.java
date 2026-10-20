package com.ape.smarthedge_risk_hub.pojo;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OriginDict {
    private int originId;
    private String originName;
    private String originShortName;
    private Date createTime;
    @TableField(exist = false)
    List<SpotProducts> spotProductsList;
}
