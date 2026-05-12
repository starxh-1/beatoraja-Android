package bms.player.beatoraja;

import java.util.HashMap;
import java.util.Map.Entry;
import java.io.File;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

public class ShaderManager {

	private static HashMap<String, ShaderProgram> shaders = new HashMap();

	public static ShaderProgram getShader(String name) {
		if (!shaders.containsKey(name)) {
			FileHandle vert = null, frag = null;
			String[] paths = {
				Config.getAbsolutePath("glsl/" + name),
				"glsl/" + name
			};

			for (String p : paths) {
				// 尝试绝对路径
				FileHandle v = Gdx.files.absolute(p + ".vert");
				FileHandle f = Gdx.files.absolute(p + ".frag");
				if (v.exists()) { vert = v; frag = f; break; }

				// 尝试本地路径
				v = Gdx.files.local(p + ".vert");
				f = Gdx.files.local(p + ".frag");
				if (v.exists()) { vert = v; frag = f; break; }

				// 尝试 internal 路径
				v = Gdx.files.internal(p + ".vert");
				f = Gdx.files.internal(p + ".frag");
				if (v.exists()) { vert = v; frag = f; break; }
			}

			// 最后的保底：Classpath (针对 jar 内置资源)
			if (vert == null || !vert.exists()) {
				// 针对 distance_field 提供硬编码兜底
				if ("distance_field".equals(name)) {
					String vertSrc = "attribute vec4 a_position;\n" +
									 "attribute vec4 a_color;\n" +
									 "attribute vec2 a_texCoord0;\n" +
									 "uniform mat4 u_projTrans;\n" +
									 "varying vec4 v_color;\n" +
									 "varying vec2 v_texCoord;\n" +
									 "void main() {\n" +
									 "    v_color = a_color;\n" +
									 "    v_color.a = v_color.a * (255.0/254.0);\n" +
									 "    v_texCoord = a_texCoord0;\n" +
									 "    gl_Position =  u_projTrans * a_position;\n" +
									 "}";
					String fragSrc = "#ifdef GL_ES\n" +
									 "precision mediump float;\n" +
									 "#endif\n" +
									 "varying vec4 v_color;\n" +
									 "varying vec2 v_texCoord;\n" +
									 "uniform sampler2D u_texture;\n" +
									 "uniform float u_outlineDistance;\n" +
									 "uniform vec4 u_outlineColor;\n" +
									 "uniform vec4 u_shadowColor;\n" +
									 "uniform float u_shadowSmoothing;\n" +
									 "uniform vec2 u_shadowOffset;\n" +
									 "void main() {\n" +
									 "    float distance = texture2D(u_texture, v_texCoord).a;\n" +
									 "    float smoothing = 1.0 / 32.0;\n" +
									 "    float alpha = smoothstep(0.5 - smoothing, 0.5 + smoothing, distance);\n" +
									 "    gl_FragColor = vec4(v_color.rgb, v_color.a * alpha);\n" +
									 "}";
					ShaderProgram shader = new ShaderProgram(vertSrc, fragSrc);
					if (shader.isCompiled()) {
						shaders.put(name, shader);
						return shader;
					}
				}
				Gdx.app.error("ShaderManager", "CRITICAL: Cannot find shader: " + name);
				return null;
			}

			ShaderProgram shader = new ShaderProgram(vert, frag);
			if (shader.isCompiled()) {
				shaders.put(name, shader);
				return shader;
			} else {
				Gdx.app.error("ShaderManager", "Shader " + name + " compile failed: " + shader.getLog());
			}
		}
		return shaders.get(name);
	}

	public static void dispose() {
		for(Entry<String, ShaderProgram> e : shaders.entrySet()) {
			if(e.getValue() != null) {
				e.getValue().dispose();
			}
		}
		shaders.clear();
	}
}
