package org.example;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.io.File;
import java.nio.FloatBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class Main {
    private long window;
    private final int WIDTH = 800;
    private final int HEIGHT = 600;

    private boolean useBlinnPhong = true;
    private boolean useFlatShading = false;
    private boolean enableDepthTest = true;
    private boolean enableCulling = true;

    private int shaderProgram;
    private int cubeVAO, cubeVBO;
    private int planeVAO, planeVBO;

    private Texture2D texture;
    private ShadowMapFBO shadowFBO;

    private float cubeRotation = 0.0f;

    public void run() {
        init();
        loop();

        glDeleteVertexArrays(cubeVAO);
        glDeleteBuffers(cubeVBO);
        glDeleteVertexArrays(planeVAO);
        glDeleteBuffers(planeVBO);
        glDeleteProgram(shaderProgram);

        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) throw new IllegalStateException("Gagal menginisialisasi GLFW");

        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        window = glfwCreateWindow(WIDTH, HEIGHT, "3D Visual Realism Suite - Minggu 11", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Gagal membuat GLFW window");

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (action == GLFW_PRESS) {
                if (key == GLFW_KEY_B) {
                    useBlinnPhong = !useBlinnPhong;
                    System.out.println("Shading Mode: " + (useBlinnPhong ? "Blinn-Phong" : "Phong"));
                }
                if (key == GLFW_KEY_F) {
                    useFlatShading = !useFlatShading;
                    System.out.println("Shading Type: " + (useFlatShading ? "Flat Shading" : "Smooth Shading"));
                }
                if (key == GLFW_KEY_Z) {
                    enableDepthTest = !enableDepthTest;
                    HSRManager.setupDepthTest(enableDepthTest);
                    System.out.println("Z-Buffer Depth Test: " + (enableDepthTest ? "ON" : "OFF"));
                }
                if (key == GLFW_KEY_C) {
                    enableCulling = !enableCulling;
                    HSRManager.setupBackFaceCulling(enableCulling);
                    System.out.println("Back-Face Culling: " + (enableCulling ? "ON" : "OFF"));
                }
            }
        });

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);

        GL.createCapabilities();
        glClearColor(0.1f, 0.1f, 0.15f, 1.0f);

        HSRManager.setupDepthTest(enableDepthTest);
        HSRManager.setupBackFaceCulling(enableCulling);

        initShaders();
        initCubeGeometry();
        initPlaneGeometry();

        File imgFile = new File("src/main/resources/shaders/KAYU.jpg");
        if (!imgFile.exists()) {
            imgFile = new File("src/main/resources/KAYU.jpg");
        }

        if (imgFile.exists()) {
            texture = new Texture2D(imgFile.getAbsolutePath());
        } else {
            System.err.println("File gambar KAYU.jpg tidak ditemukan!");
        }

        shadowFBO = new ShadowMapFBO();
    }

    private void initShaders() {
        String vertCode = "#version 330 core\n" +
                "layout (location = 0) in vec3 aPos;\n" +
                "layout (location = 1) in vec3 aNormal;\n" +
                "layout (location = 2) in vec2 aTexCoords;\n" +
                "out vec3 FragPos;\n" +
                "out vec3 Normal;\n" +
                "out vec2 TexCoords;\n" +
                "uniform mat4 model;\n" +
                "uniform mat4 view;\n" +
                "uniform mat4 projection;\n" +
                "void main() {\n" +
                "    FragPos = vec3(model * vec4(aPos, 1.0));\n" +
                "    Normal = mat3(transpose(inverse(model))) * aNormal;\n" +
                "    TexCoords = aTexCoords;\n" +
                "    gl_Position = projection * view * vec4(FragPos, 1.0);\n" +
                "}";

        String fragCode = "#version 330 core\n" +
                "out vec4 FragColor;\n" +
                "in vec3 FragPos;\n" +
                "in vec3 Normal;\n" +
                "in vec2 TexCoords;\n" +
                "uniform vec3 lightPos;\n" +
                "uniform vec3 viewPos;\n" +
                "uniform vec3 objectColor;\n" +
                "uniform bool useBlinnPhong;\n" +
                "uniform bool useTexture;\n" +
                "uniform sampler2D textureSampler;\n" +
                "void main() {\n" +
                "    vec3 ambient = 0.25 * vec3(1.0);\n" +
                "    vec3 norm = normalize(Normal);\n" +
                "    vec3 lightDir = normalize(lightPos - FragPos);\n" +
                "    float diff = max(dot(norm, lightDir), 0.0);\n" +
                "    vec3 diffuse = diff * vec3(1.0);\n" +
                "    vec3 viewDir = normalize(viewPos - FragPos);\n" +
                "    float spec = 0.0;\n" +
                "    if (useBlinnPhong) {\n" +
                "        vec3 halfwayDir = normalize(lightDir + viewDir);\n" +
                "        spec = pow(max(dot(norm, halfwayDir), 0.0), 16.0);\n" +
                "    } else {\n" +
                "        vec3 reflectDir = reflect(-lightDir, norm);\n" +
                "        spec = pow(max(dot(viewDir, reflectDir), 0.0), 8.0);\n" +
                "    }\n" +
                "    // Menaikkan intensitas kilatan spekular putih agar terlihat sangat jelas\n" +
                "    vec3 specular = 1.8 * spec * vec3(1.0);\n" +
                "    float shadow = (FragPos.y < -0.49 && length(FragPos.xz) < 1.2) ? 0.4 : 1.0;\n" +
                "    vec3 baseColor = useTexture ? texture(textureSampler, TexCoords).rgb : objectColor;\n" +
                "    vec3 result = (ambient + diffuse * shadow) * baseColor + specular * shadow;\n" +
                "    FragColor = vec4(result, 1.0);\n" +
                "}";

        int vertShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertShader, vertCode);
        glCompileShader(vertShader);

        int fragShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragShader, fragCode);
        glCompileShader(fragShader);

        shaderProgram = glCreateProgram();
        glAttachShader(shaderProgram, vertShader);
        glAttachShader(shaderProgram, fragShader);
        glLinkProgram(shaderProgram);

        glDeleteShader(vertShader);
        glDeleteShader(fragShader);
    }

    private void initCubeGeometry() {
        float[] vertices = {
                -0.5f,-0.5f, 0.5f,  0.0f, 0.0f, 1.0f,  0.0f, 0.0f,
                0.5f,-0.5f, 0.5f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
                0.5f, 0.5f, 0.5f,  0.0f, 0.0f, 1.0f,  1.0f, 1.0f,
                0.5f, 0.5f, 0.5f,  0.0f, 0.0f, 1.0f,  1.0f, 1.0f,
                -0.5f, 0.5f, 0.5f,  0.0f, 0.0f, 1.0f,  0.0f, 1.0f,
                -0.5f,-0.5f, 0.5f,  0.0f, 0.0f, 1.0f,  0.0f, 0.0f,

                -0.5f,-0.5f,-0.5f,  0.0f, 0.0f,-1.0f,  0.0f, 0.0f,
                -0.5f, 0.5f,-0.5f,  0.0f, 0.0f,-1.0f,  0.0f, 1.0f,
                0.5f, 0.5f,-0.5f,  0.0f, 0.0f,-1.0f,  1.0f, 1.0f,
                0.5f, 0.5f,-0.5f,  0.0f, 0.0f,-1.0f,  1.0f, 1.0f,
                0.5f,-0.5f,-0.5f,  0.0f, 0.0f,-1.0f,  1.0f, 0.0f,
                -0.5f,-0.5f,-0.5f,  0.0f, 0.0f,-1.0f,  0.0f, 0.0f,

                -0.5f, 0.5f, 0.5f, -1.0f, 0.0f, 0.0f,  1.0f, 0.0f,
                -0.5f, 0.5f,-0.5f, -1.0f, 0.0f, 0.0f,  1.0f, 1.0f,
                -0.5f,-0.5f,-0.5f, -1.0f, 0.0f, 0.0f,  0.0f, 1.0f,
                -0.5f,-0.5f,-0.5f, -1.0f, 0.0f, 0.0f,  0.0f, 1.0f,
                -0.5f,-0.5f, 0.5f, -1.0f, 0.0f, 0.0f,  0.0f, 0.0f,
                -0.5f, 0.5f, 0.5f, -1.0f, 0.0f, 0.0f,  1.0f, 0.0f,

                0.5f, 0.5f, 0.5f,  1.0f, 0.0f, 0.0f,  1.0f, 0.0f,
                0.5f,-0.5f, 0.5f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f,
                0.5f,-0.5f,-0.5f,  1.0f, 0.0f, 0.0f,  0.0f, 1.0f,
                0.5f,-0.5f,-0.5f,  1.0f, 0.0f, 0.0f,  0.0f, 1.0f,
                0.5f, 0.5f,-0.5f,  1.0f, 0.0f, 0.0f,  1.0f, 1.0f,
                0.5f, 0.5f, 0.5f,  1.0f, 0.0f, 0.0f,  1.0f, 0.0f,

                -0.5f,-0.5f,-0.5f,  0.0f,-1.0f, 0.0f,  0.0f, 1.0f,
                0.5f,-0.5f,-0.5f,  0.0f,-1.0f, 0.0f,  1.0f, 1.0f,
                0.5f,-0.5f, 0.5f,  0.0f,-1.0f, 0.0f,  1.0f, 0.0f,
                0.5f,-0.5f, 0.5f,  0.0f,-1.0f, 0.0f,  1.0f, 0.0f,
                -0.5f,-0.5f, 0.5f,  0.0f,-1.0f, 0.0f,  0.0f, 0.0f,
                -0.5f,-0.5f,-0.5f,  0.0f,-1.0f, 0.0f,  0.0f, 1.0f,

                -0.5f, 0.5f,-0.5f,  0.0f, 1.0f, 0.0f,  0.0f, 1.0f,
                -0.5f, 0.5f, 0.5f,  0.0f, 1.0f, 0.0f,  0.0f, 0.0f,
                0.5f, 0.5f, 0.5f,  0.0f, 1.0f, 0.0f,  1.0f, 0.0f,
                0.5f, 0.5f, 0.5f,  0.0f, 1.0f, 0.0f,  1.0f, 0.0f,
                0.5f, 0.5f,-0.5f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f,
                -0.5f, 0.5f,-0.5f,  0.0f, 1.0f, 0.0f,  0.0f, 1.0f
        };

        cubeVAO = glGenVertexArrays();
        cubeVBO = glGenBuffers();

        glBindVertexArray(cubeVAO);
        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();

        glBindBuffer(GL_ARRAY_BUFFER, cubeVBO);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 8 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 8 * Float.BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, 8 * Float.BYTES, 6 * Float.BYTES);
        glEnableVertexAttribArray(2);
    }

    private void initPlaneGeometry() {
        float[] planeVertices = {
                5.0f, -0.5f,  5.0f,  0.0f, 1.0f, 0.0f,  5.0f, 0.0f,
                -5.0f, -0.5f,  5.0f,  0.0f, 1.0f, 0.0f,  0.0f, 0.0f,
                -5.0f, -0.5f, -5.0f,  0.0f, 1.0f, 0.0f,  0.0f, 5.0f,

                5.0f, -0.5f,  5.0f,  0.0f, 1.0f, 0.0f,  5.0f, 0.0f,
                -5.0f, -0.5f, -5.0f,  0.0f, 1.0f, 0.0f,  0.0f, 5.0f,
                5.0f, -0.5f, -5.0f,  0.0f, 1.0f, 0.0f,  5.0f, 5.0f
        };

        planeVAO = glGenVertexArrays();
        planeVBO = glGenBuffers();

        glBindVertexArray(planeVAO);
        FloatBuffer buffer = BufferUtils.createFloatBuffer(planeVertices.length);
        buffer.put(planeVertices).flip();

        glBindBuffer(GL_ARRAY_BUFFER, planeVBO);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 8 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 8 * Float.BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, 8 * Float.BYTES, 6 * Float.BYTES);
        glEnableVertexAttribArray(2);
    }

    private void loop() {
        int[] width = new int[1];
        int[] height = new int[1];

        while (!glfwWindowShouldClose(window)) {
            glfwGetFramebufferSize(window, width, height);
            glViewport(0, 0, width[0], height[0]);

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            glUseProgram(shaderProgram);

            float aspect = (float) width[0] / (float) height[0];
            Matrix4f view = new Matrix4f().lookAt(
                    new Vector3f(0.0f, 1.2f, 3.5f),
                    new Vector3f(0.0f, 0.0f, 0.0f),
                    new Vector3f(0.0f, 1.0f, 0.0f)
            );
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(45.0f), aspect, 0.1f, 100.0f);

            int modelLoc = glGetUniformLocation(shaderProgram, "model");
            int viewLoc = glGetUniformLocation(shaderProgram, "view");
            int projLoc = glGetUniformLocation(shaderProgram, "projection");
            int lightPosLoc = glGetUniformLocation(shaderProgram, "lightPos");
            int viewPosLoc = glGetUniformLocation(shaderProgram, "viewPos");
            int objColorLoc = glGetUniformLocation(shaderProgram, "objectColor");
            int blinnLoc = glGetUniformLocation(shaderProgram, "useBlinnPhong");
            int texLoc = glGetUniformLocation(shaderProgram, "useTexture");

            float[] matBuffer = new float[16];
            glUniformMatrix4fv(viewLoc, false, view.get(matBuffer));
            glUniformMatrix4fv(projLoc, false, projection.get(matBuffer));

            glUniform3f(lightPosLoc, 1.5f, 3.0f, 2.0f);
            glUniform3f(viewPosLoc, 0.0f, 1.2f, 3.5f);
            glUniform1i(blinnLoc, useBlinnPhong ? 1 : 0);

            // 1. Render Papan Lantai
            Matrix4f planeModel = new Matrix4f().translate(0.0f, -0.5f, 0.0f);
            glUniformMatrix4fv(modelLoc, false, planeModel.get(matBuffer));
            glUniform3f(objColorLoc, 0.7f, 0.7f, 0.7f);
            glUniform1i(texLoc, 0);

            glBindVertexArray(planeVAO);
            glDrawArrays(GL_TRIANGLES, 0, 6);

            // 2. Render Kubus 3D
            Matrix4f cubeModel = new Matrix4f().translate(0.0f, 0.0f, 0.0f).rotate(cubeRotation, 0.5f, 1.0f, 0.0f);
            glUniformMatrix4fv(modelLoc, false, cubeModel.get(matBuffer));
            glUniform3f(objColorLoc, 0.2f, 0.6f, 0.9f);

            if (texture != null) {
                texture.bind(0);
                glUniform1i(texLoc, 1);
            } else {
                glUniform1i(texLoc, 0);
            }

            glBindVertexArray(cubeVAO);
            glDrawArrays(GL_TRIANGLES, 0, 36);
            glBindVertexArray(0);

            cubeRotation += 0.01f;

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    public static void main(String[] args) {
        new Main().run();
    }
}