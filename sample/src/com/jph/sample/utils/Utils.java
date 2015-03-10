package com.jph.sample.utils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 
 * 工具类
 * @author JPH
 * @date 2015-3-10 下午12:38:00
 */
public class Utils {

	public static String getCurrentTime(String format) {
		Date date = new Date();
		SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.getDefault());
		String currentTime = sdf.format(date);
		return currentTime;
	}

	public static String getCurrentTime() {
		return getCurrentTime("yyyy-MM-dd  HH:mm:ss");
	}
}
