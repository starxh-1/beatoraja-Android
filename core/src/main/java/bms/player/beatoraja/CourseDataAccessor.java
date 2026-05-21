package bms.player.beatoraja;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;

import java.io.*;

/**
 * コースデータへのアクセス
 *
 * @author exch
 */
public class CourseDataAccessor {

    private final String coursedir;

    public CourseDataAccessor(String path) {
        coursedir = path;
        File dir = new File(coursedir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("beatoraja.root", "."), coursedir);
        }
        dir.mkdirs();
    }

    /**
     * 全てのコースデータを読み込む
     *
     * @return 全てのキャッシュされた難易度表データ
     */
    public CourseData[] readAll() {
        String[] names = readAllNames();
        java.util.List<CourseData> result = new java.util.ArrayList<>();
        for (String name : names) {
            for (CourseData cd : read(name)) {
                result.add(cd);
            }
        }
        return result.toArray(new CourseData[0]);
    }

    public String[] readAllNames() {
        File dir = new File(coursedir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("beatoraja.root", "."), coursedir);
        }
        String[] files = dir.list();
        if (files != null) {
            java.util.List<String> result = new java.util.ArrayList<>();
            for (String f : files) {
                if (f.endsWith(".json")) {
                    result.add(f.substring(0, f.lastIndexOf('.')));
                }
            }
            return result.toArray(new String[0]);
        }
        // Fallback to internal assets if it's a relative path and doesn't exist in filesystem
        try {
            com.badlogic.gdx.files.FileHandle[] internalFiles = com.badlogic.gdx.Gdx.files.internal(coursedir).list();
            if (internalFiles != null && internalFiles.length > 0) {
                java.util.List<String> names = new java.util.ArrayList<>();
                for (com.badlogic.gdx.files.FileHandle file : internalFiles) {
                    if (file.extension().equals("json")) {
                        names.add(file.nameWithoutExtension());
                    }
                }
                return names.toArray(new String[0]);
            }
        } catch (Exception ex) {}
        return new String[0];
    }

    public CourseData[] read(String name) {
        File file = new File(coursedir + "/" + name + ".json");
        boolean isList = false;
        try {
            Json json = new Json();
			json.setIgnoreUnknownFields(true);
            CourseData[] courses =  json.fromJson(CourseData[].class,
                    new BufferedInputStream(new FileInputStream(file)));
            CourseData[] result = new CourseData[courses.length];
            int count = 0;
            for (CourseData c : courses) {
                if (c.validate()) {
                    result[count++] = c;
                }
            }
            CourseData[] trimmed = new CourseData[count];
            System.arraycopy(result, 0, trimmed, 0, count);
            return trimmed;
        } catch(Throwable e) {

        }
        if(!isList) {
            try {
                Json json = new Json();
				json.setIgnoreUnknownFields(true);
                CourseData course = json.fromJson(CourseData.class,
                        new BufferedInputStream(new FileInputStream(file)));
            	if(course.validate()) {
            		return new CourseData[]{course};
            	}
            } catch(Throwable e) {
            }
        }
        return new CourseData[0] ;
    }
    /**
     * コースデータを保存する
     *
     * @param cd コースデータ
     */
    public void write(String name, CourseData[] cd) {
        try {
        	for (CourseData c : cd) { c.shrink(); }
            Json json = new Json();
            json.setOutputType(JsonWriter.OutputType.json);
            OutputStreamWriter fw = new OutputStreamWriter(new BufferedOutputStream(
                    new FileOutputStream(coursedir + "/" + name + ".json")), "UTF-8");
            fw.write(json.prettyPrint(cd));
            fw.flush();
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}
