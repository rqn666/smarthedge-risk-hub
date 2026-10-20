package com.ape.smarthedge_risk_hub.util;

import com.ape.smarthedge_risk_hub.pojo.SpotProducts;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DateHandle {
    public static <T> void dateHandle(T obj, String dateFieldName) throws ParseException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String formattedDate = simpleDateFormat.format(new Date());
        Date parse = simpleDateFormat.parse(formattedDate);
        String setterMethod = "set" + dateFieldName.substring(0, 1).toUpperCase() + dateFieldName.substring(1);
        Method setter = obj.getClass().getMethod(setterMethod, Date.class);
        setter.invoke(obj, parse);
    }
}
