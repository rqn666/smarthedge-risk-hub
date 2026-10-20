package com.ape.smarthedge_risk_hub.pojo;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SpotProducts {
    private int id;
    private String spotName;
    private String spotSpec;
    private int originId;
    private String spotUnit;
    private Date updateTime;
    @TableField(exist = false)
    private OriginDict originDict;

    public SpotProducts(String spotName, String spotSpec, int originId, String spotUnit) {
        this.spotName = spotName;
        this.spotSpec = spotSpec;
        this.originId = originId;
        this.spotUnit = spotUnit;
        this.updateTime = new Date();
    }

    @Override
    public String toString() {
        return "SpotProducts{" +
                "id=" + id +
                ", SpotName='" + spotName + '\'' +
                ", SpotSpec='" + spotSpec + '\'' +
                ", originId=" + originId +
                ", spotUnit='" + spotUnit + '\'' +
                ", updateTime=" + updateTime +
                '}';
    }
}
