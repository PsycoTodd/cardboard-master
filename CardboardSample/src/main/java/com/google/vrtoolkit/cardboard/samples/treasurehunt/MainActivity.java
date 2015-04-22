/*
 * Copyright 2014 Google Inc. All Rights Reserved.

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.vrtoolkit.cardboard.samples.treasurehunt;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import com.google.vrtoolkit.cardboard.*;
import android.util.FloatMath;

import javax.microedition.khronos.egl.EGLConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
/**
 * A Cardboard sample application.
 */
public class MainActivity extends CardboardActivity implements CardboardView.StereoRenderer {

    private static final String TAG = "MainActivity";

    private static final float CAMERA_Z = 0.01f;
    private static final float CAMERA_Y  = -17f;
    private static final float TIME_DELTA = 0.3f;

    private static final float YAW_LIMIT = 0.72f; //0.12f in origin
    private static final float PITCH_LIMIT = 0.72f;

    // We keep the light always position just above the user.
    private final float[] mLightPosInWorldSpace = new float[] {0.0f, 2.0f, 0.0f, 1.0f};
    private final float[] mLightPosInEyeSpace = new float[4];

    private static final int COORDS_PER_VERTEX = 3;

    private final WorldLayoutData DATA = new WorldLayoutData();

    private FloatBuffer mFloorVertices;
    private FloatBuffer mFloorColors;
    private FloatBuffer mFloorNormals;

    //Todd
    private FloatBuffer mMazeVertices;
    private FloatBuffer mMazeColors;
    private FloatBuffer mMazeNormals;

    private FloatBuffer mCubeVertices;
    private FloatBuffer mCubeColors;
    private FloatBuffer mCubeFoundColors;
    private FloatBuffer mCubeNormals;

    private int mGlProgram;
    private int mPositionParam;
    private int mNormalParam;
    private int mColorParam;
    private int mModelViewProjectionParam;
    private int mLightPosParam;
    private int mModelViewParam;
    private int mModelParam;
    private int mIsFloorParam;

    private float[] mModelCube;
    private float[] mCamera;
    private float[] mView;
    private float[] mHeadView;
    private float[] mModelViewProjection;
    private float[] mModelView;
    private float[] tempCamera; //used to test collision

    private float[] mModelFloor;
    private float[] mModelMaze;

    private int mScore = 0;
    private float mObjectDistance = 20f;
    private float mFloorDepth = 20f;
    private boolean mEnlarged = false; // Keep a copy of the cube so we know the original size
    private boolean GODMODE = false; // We can get through walls in this mode

    private Vibrator mVibrator;

    private CardboardOverlayView mOverlayView;

    private boolean isWalking = false;
    private boolean mUpdateHeadDir = true;
    private float[] mWalkingDir;

    /**
     * Converts a raw text file, saved as a resource, into an OpenGL ES shader
     * @param type The type of shader we will be creating.
     * @param resId The resource ID of the raw text file about to be turned into a shader.
     * @return
     */
    private int loadGLShader(int type, int resId) {
        String code = readRawTextFile(resId);
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);

        // Get the compilation status.
        final int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);

        // If the compilation failed, delete the shader.
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }

        if (shader == 0) {
            throw new RuntimeException("Error creating shader.");
        }

        return shader;
    }

    /**
     * Checks if we've had an error inside of OpenGL ES, and if so what that error is.
     * @param func
     */
    private static void checkGLError(String func) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, func + ": glError " + error);
            throw new RuntimeException(func + ": glError " + error);
        }
    }

    /**
     * Sets the view to our CardboardView and initializes the transformation matrices we will use
     * to render our scene.
     * @param savedInstanceState
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.common_ui);
        CardboardView cardboardView = (CardboardView) findViewById(R.id.cardboard_view);
        cardboardView.setRenderer(this);
        setCardboardView(cardboardView);

        mModelCube = new float[16];
        mCamera = new float[16];
        mView = new float[16];
        mModelViewProjection = new float[16];
        mModelView = new float[16];
        mModelFloor = new float[16];
        mModelMaze = new float[16];
        mHeadView = new float[16];
        mWalkingDir = new float[3];
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        tempCamera = new float[16];


        mOverlayView = (CardboardOverlayView) findViewById(R.id.overlay);
        mOverlayView.show3DToast("Pull the magnet when you find an object.");
    }

    @Override
    public void onRendererShutdown() {
        Log.i(TAG, "onRendererShutdown");
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        Log.i(TAG, "onSurfaceChanged");
    }

    /**
     * Creates the buffers we use to store information about the 3D world. OpenGL doesn't use Java
     * arrays, but rather needs data in a format it can understand. Hence we use ByteBuffers.
     * @param config The EGL configuration used when creating the surface.
     */
    @Override
    public void onSurfaceCreated(EGLConfig config) {
        Log.i(TAG, "onSurfaceCreated");
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 0.5f); // Dark background so text shows up well

        ByteBuffer bbVertices = ByteBuffer.allocateDirect(DATA.CUBE_COORDS.length * 4);
        bbVertices.order(ByteOrder.nativeOrder());
        mCubeVertices = bbVertices.asFloatBuffer();
        mCubeVertices.put(DATA.CUBE_COORDS);
        mCubeVertices.position(0);

        ByteBuffer bbColors = ByteBuffer.allocateDirect(DATA.CUBE_COLORS.length * 4);
        bbColors.order(ByteOrder.nativeOrder());
        mCubeColors = bbColors.asFloatBuffer();
        mCubeColors.put(DATA.CUBE_COLORS);
        mCubeColors.position(0);

        ByteBuffer bbFoundColors = ByteBuffer.allocateDirect(DATA.CUBE_FOUND_COLORS.length * 4);
        bbFoundColors.order(ByteOrder.nativeOrder());
        mCubeFoundColors = bbFoundColors.asFloatBuffer();
        mCubeFoundColors.put(DATA.CUBE_FOUND_COLORS);
        mCubeFoundColors.position(0);

        ByteBuffer bbNormals = ByteBuffer.allocateDirect(DATA.CUBE_NORMALS.length * 4);
        bbNormals.order(ByteOrder.nativeOrder());
        mCubeNormals = bbNormals.asFloatBuffer();
        mCubeNormals.put(DATA.CUBE_NORMALS);
        mCubeNormals.position(0);

        // make a floor
        ByteBuffer bbFloorVertices = ByteBuffer.allocateDirect(DATA.FLOOR_COORDS.length * 4);
        bbFloorVertices.order(ByteOrder.nativeOrder());
        mFloorVertices = bbFloorVertices.asFloatBuffer();
        mFloorVertices.put(DATA.FLOOR_COORDS);
        mFloorVertices.position(0);

        ByteBuffer bbFloorNormals = ByteBuffer.allocateDirect(DATA.FLOOR_NORMALS.length * 4);
        bbFloorNormals.order(ByteOrder.nativeOrder());
        mFloorNormals = bbFloorNormals.asFloatBuffer();
        mFloorNormals.put(DATA.FLOOR_NORMALS);
        mFloorNormals.position(0);

        ByteBuffer bbFloorColors = ByteBuffer.allocateDirect(DATA.FLOOR_COLORS.length * 4);
        bbFloorColors.order(ByteOrder.nativeOrder());
        mFloorColors = bbFloorColors.asFloatBuffer();
        mFloorColors.put(DATA.FLOOR_COLORS);
        mFloorColors.position(0);

        // Todd: Now we try to register our maze
        ByteBuffer bbMazeVertices = ByteBuffer.allocateDirect(DATA.MAZE_COORDS.length * 4);
        bbMazeVertices.order(ByteOrder.nativeOrder());
        mMazeVertices = bbMazeVertices.asFloatBuffer();
        mMazeVertices.put(DATA.MAZE_COORDS);
        mMazeVertices.position(0);

        ByteBuffer bbMazeNormals = ByteBuffer.allocateDirect(DATA.MAZE_NORMALS.length * 4);
        bbMazeNormals.order(ByteOrder.nativeOrder());
        mMazeNormals = bbMazeNormals.asFloatBuffer();
        mMazeNormals.put(DATA.MAZE_NORMALS);
        mMazeNormals.position(0);

        ByteBuffer bbMazeColors = ByteBuffer.allocateDirect(DATA.MAZE_COLORS.length * 4);
        bbMazeColors.order(ByteOrder.nativeOrder());
        mMazeColors = bbMazeColors.asFloatBuffer();
        mMazeColors.put(DATA.MAZE_COLORS);
        mMazeColors.position(0);

        int vertexShader = loadGLShader(GLES20.GL_VERTEX_SHADER, R.raw.light_vertex);
        int gridShader = loadGLShader(GLES20.GL_FRAGMENT_SHADER, R.raw.grid_fragment);

        mGlProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mGlProgram, vertexShader);
        GLES20.glAttachShader(mGlProgram, gridShader);
        GLES20.glLinkProgram(mGlProgram);

        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        // Object first appears directly in front of user
        Matrix.setIdentityM(mModelCube, 0);
        Matrix.translateM(mModelCube, 0, 0, CAMERA_Y, -mObjectDistance);

        Matrix.setIdentityM(mModelFloor, 0);
        Matrix.translateM(mModelFloor, 0, 0, -mFloorDepth, 0); // Floor appears below user

        Matrix.setIdentityM(mModelMaze, 0);
        Matrix.translateM(mModelMaze, 0, 0, -40, 0);

        // Build the camera matrix and apply it to the ModelView. Todd: put the matrix lower to the floor by -17 = CAMERA_Y
        Matrix.setLookAtM(mCamera, 0, 0.0f, CAMERA_Y, CAMERA_Z, 0.0f, CAMERA_Y, 0.0f, 0.0f, 1.0f, 0.0f);

        checkGLError("onSurfaceCreated");
    }

    /**
     * Converts a raw text file into a string.
     * @param resId The resource ID of the raw text file about to be turned into a shader.
     * @return
     */
    private String readRawTextFile(int resId) {
        InputStream inputStream = getResources().openRawResource(resId);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * Try to see if the current point is inside the geometry or not
     * Just realize that this collision detection is simply a inside outside test of point
     * @param pt The location of the camera as (x, y, z)
     * @param geometry The object it may hit
     * @return
     */
    protected boolean notCollision(float [] pt, FloatBuffer geometry){

        boolean [] hit = {false, false, false, false};
        float[] boundRange = {0.5f,0.5f,0.5f,-0.5f,-0.5f,0.5f,-0.5f,-0.5f};
        int ptNum_Sqr = 6*3;
        // in this maze in a flat surface, we actually can do a point inside outside polygon test in XZ plane
        float xc = -pt[12], zc = -pt[14]; //-40 for x since we move the maze negative 40 before we set it.
        float x, z;
        // Now we just traverse the entire maze wall and only focus on boundary defined by the XZ
        int ptNum = geometry.capacity();
        int range = ptNum/ptNum_Sqr; //Two triangle per square wall
        float p1x, p1z, p2x, p2z;
     //   float [] tempBuffer = new float[ptNum_Sqr];
     //   geometry.position(0);
        for(int i=0; i<range; ++i){
          //  geometry.get(tempBuffer, 0, ptNum_Sqr);
            p1x = geometry.get(i*ptNum_Sqr+0); p1z = geometry.get(i*ptNum_Sqr+2);
            p2x = geometry.get(i*ptNum_Sqr+3); p2z = geometry.get(i*ptNum_Sqr+5);
       /*     p1x = DATA.MAZE_COORDS[i*ptNum_Sqr+0]; p1z = DATA.MAZE_COORDS[i*ptNum_Sqr+2];
            p2x = DATA.MAZE_COORDS[i*ptNum_Sqr+3]; p2z = DATA.MAZE_COORDS[i*ptNum_Sqr+5];*/

            for(int j=0; j<4; ++j) { // we test on four bounding point of the camera
                x = xc+boundRange[2*j];
                z = zc+boundRange[2*j+1];
                if ((p1z < z && p2z >= z || p2z < z && p1z >= z) && (p1x <= x || p2x <= x)) {
                    if (p1x + (z - p1z) / (p2z - p1z) * (p2x - p1x) < x)
                        hit[j] = !hit[j];
                }
            }
        }
        boolean res = true;
        for(int i=0; i<4;++i)
            res &= hit[i];
        return res;
    }
    /**
     * Prepares OpenGL ES before we draw a frame.
     * @param headTransform The head transformation in the new frame.
     */
    @Override
    public void onNewFrame(HeadTransform headTransform) {
        GLES20.glUseProgram(mGlProgram);

        mModelViewProjectionParam = GLES20.glGetUniformLocation(mGlProgram, "u_MVP");
        mLightPosParam = GLES20.glGetUniformLocation(mGlProgram, "u_LightPos");
        mModelViewParam = GLES20.glGetUniformLocation(mGlProgram, "u_MVMatrix");
        mModelParam = GLES20.glGetUniformLocation(mGlProgram, "u_Model");
        mIsFloorParam = GLES20.glGetUniformLocation(mGlProgram, "u_IsFloor");

        // Build the Model part of the ModelView matrix.
        Matrix.rotateM(mModelCube, 0, TIME_DELTA, 0.5f, 0.5f, 1.0f);

        // Build the camera matrix and apply it to the ModelView. Todd: put the matrix lower
     //   Matrix.setLookAtM(mCamera, 0, 0.0f, CAMERA_Y, CAMERA_Z, 0.0f, CAMERA_Y, 0.0f, 0.0f, 1.0f, 0.0f);
        if (isWalking) {
            if (mUpdateHeadDir == true) {// This time we need to get the head pose information
                headTransform.getForwardVector(mWalkingDir, 0);
                mUpdateHeadDir = false;
            }
            float sqrsumXZ = FloatMath.sqrt(mWalkingDir[0]*mWalkingDir[0] + mWalkingDir[2]*mWalkingDir[2]);
            float xforce = TIME_DELTA*0.4f * mWalkingDir[0]/sqrsumXZ;
            float zforce = TIME_DELTA*0.4f * mWalkingDir[2]/sqrsumXZ;

            // Todd: we also need to stop the the camera movement if user hit the wall
            System.arraycopy(mCamera, 0, tempCamera, 0, mCamera.length);
            Matrix.translateM(tempCamera, 0, xforce, 0, -zforce);
            if(!GODMODE && notCollision(tempCamera,mMazeVertices )){ //we only move when we don't hit anything
                System.arraycopy(tempCamera, 0, mCamera, 0, tempCamera.length);
            }
        }

        headTransform.getHeadView(mHeadView, 0);

        checkGLError("onReadyToDraw");
    }

    /**
     * Draws a frame for an eye. The transformation for that eye (from the camera) is passed in as
     * a parameter.
     * @param transform The transformations to apply to render this eye.
     */
    @Override
    public void onDrawEye(EyeTransform transform) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        mPositionParam = GLES20.glGetAttribLocation(mGlProgram, "a_Position");
        mNormalParam = GLES20.glGetAttribLocation(mGlProgram, "a_Normal");
        mColorParam = GLES20.glGetAttribLocation(mGlProgram, "a_Color");

        GLES20.glEnableVertexAttribArray(mPositionParam);
        GLES20.glEnableVertexAttribArray(mNormalParam);
        GLES20.glEnableVertexAttribArray(mColorParam);
        checkGLError("mColorParam");

        // Apply the eye transformation to the camera.
        Matrix.multiplyMM(mView, 0, transform.getEyeView(), 0, mCamera, 0);

        // Set the position of the light
        Matrix.multiplyMV(mLightPosInEyeSpace, 0, mView, 0, mLightPosInWorldSpace, 0);
        GLES20.glUniform3f(mLightPosParam, mLightPosInEyeSpace[0], mLightPosInEyeSpace[1],
                mLightPosInEyeSpace[2]);

        // Build the ModelView and ModelViewProjection matrices
        // for calculating cube position and light.
        Matrix.multiplyMM(mModelView, 0, mView, 0, mModelCube, 0);
        Matrix.multiplyMM(mModelViewProjection, 0, transform.getPerspective(), 0, mModelView, 0);
        drawCube();

        // Set mModelView for the floor, so we draw floor in the correct location
        Matrix.multiplyMM(mModelView, 0, mView, 0, mModelFloor, 0);
        Matrix.multiplyMM(mModelViewProjection, 0, transform.getPerspective(), 0,
            mModelView, 0);
        drawFloor(transform.getPerspective());

        // Now we just use the matrix of the floor and try the maze, let's see how it looks like
        Matrix.multiplyMM(mModelView, 0, mView, 0, mModelMaze, 0);
        Matrix.multiplyMM(mModelViewProjection, 0, transform.getPerspective(), 0,
                mModelView, 0);
       drawMaze(transform.getPerspective());
    }

    @Override
    public void onFinishFrame(Viewport viewport) {
    }

    /**
     * Draw the cube. We've set all of our transformation matrices. Now we simply pass them into
     * the shader.
     */
    public void drawCube() {
        // This is not the floor!
        GLES20.glUniform1f(mIsFloorParam, 0f);

        // Set the Model in the shader, used to calculate lighting
        GLES20.glUniformMatrix4fv(mModelParam, 1, false, mModelCube, 0);

        // Set the ModelView in the shader, used to calculate lighting
        GLES20.glUniformMatrix4fv(mModelViewParam, 1, false, mModelView, 0);

        // Set the position of the cube
        GLES20.glVertexAttribPointer(mPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT,
                false, 0, mCubeVertices);

        // Set the ModelViewProjection matrix in the shader.
        GLES20.glUniformMatrix4fv(mModelViewProjectionParam, 1, false, mModelViewProjection, 0);

        // Set the normal positions of the cube, again for shading
        GLES20.glVertexAttribPointer(mNormalParam, 3, GLES20.GL_FLOAT,
                false, 0, mCubeNormals);



        if (isLookingAtObject()) {
            GLES20.glVertexAttribPointer(mColorParam, 4, GLES20.GL_FLOAT, false,
                    0, mCubeFoundColors);
            // Try to scale the cube bigger  when user look at it
            if(mEnlarged == false) {
                float objectScalingFactor = 2.0f;
                Matrix.scaleM(mModelCube, 0, objectScalingFactor, objectScalingFactor, objectScalingFactor);
                //Matrix.multiplyMV(scaleRes, 0, scaleMatrix, 0, mModelCube, 0);
                //Matrix.setIdentityM(mModelCube, 0);
                mEnlarged = true;
            }

        } else {
            GLES20.glVertexAttribPointer(mColorParam, 4, GLES20.GL_FLOAT, false,
                    0, mCubeColors);
            if(mEnlarged == true) {
                float objectScalingFactor = 0.5f;
                Matrix.scaleM(mModelCube, 0, objectScalingFactor, objectScalingFactor, objectScalingFactor);
                mEnlarged = false;
            }
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
        checkGLError("Drawing cube");
    }

    /**
     * Draw the floor. This feeds in data for the floor into the shader. Note that this doesn't
     * feed in data about position of the light, so if we rewrite our code to draw the floor first,
     * the lighting might look strange.
     */
    public void drawFloor(float[] perspective) {
        // This is the floor!
        GLES20.glUniform1f(mIsFloorParam, 1f);

        // Set ModelView, MVP, position, normals, and color
        GLES20.glUniformMatrix4fv(mModelParam, 1, false, mModelFloor, 0);
        GLES20.glUniformMatrix4fv(mModelViewParam, 1, false, mModelView, 0);
        GLES20.glUniformMatrix4fv(mModelViewProjectionParam, 1, false, mModelViewProjection, 0);
        GLES20.glVertexAttribPointer(mPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT,
                false, 0, mFloorVertices);
        GLES20.glVertexAttribPointer(mNormalParam, 3, GLES20.GL_FLOAT, false, 0, mFloorNormals);
        GLES20.glVertexAttribPointer(mColorParam, 4, GLES20.GL_FLOAT, false, 0, mFloorColors);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);

        checkGLError("drawing floor");
    }

    /**
     * Draw the maze.Need to draw after the light has been set up or the shade may look strange (base
     * on what google said.
     */
    public void drawMaze(float[] perspective) {
        // This is the floor, kinda of!
        GLES20.glUniform1f(mIsFloorParam, 1f);

        // Set ModelView, MVP, position, normals, and color
        GLES20.glUniformMatrix4fv(mModelParam, 1, false, mModelFloor, 0);
        GLES20.glUniformMatrix4fv(mModelViewParam, 1, false, mModelView, 0);
        GLES20.glUniformMatrix4fv(mModelViewProjectionParam, 1, false, mModelViewProjection, 0);
        GLES20.glVertexAttribPointer(mPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT,
                false, 0, mMazeVertices);
        GLES20.glVertexAttribPointer(mNormalParam, 3, GLES20.GL_FLOAT, false, 0, mMazeNormals);
        GLES20.glVertexAttribPointer(mColorParam, 4, GLES20.GL_FLOAT, false, 0, mMazeColors);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6*10); //If you have more face, you need to draw more here

        checkGLError("drawing maze!");
    }

    /**
     * Increment the score, hide the object, and give feedback if the user pulls the magnet while
     * looking at the object. Otherwise, remind the user what to do.
     */
    @Override
    public void onCardboardTrigger() {
        Log.i(TAG, "onCardboardTrigger");

        if (isLookingAtObject()) {
            mScore++;
            mOverlayView.show3DToast("Found it! Look around for another one.\nScore = " + mScore);
            hideObject();
        } else {
            mOverlayView.show3DToast("Look around to find the object!");
        }

        // Todd: Self-implemented cardboard trigger event so when push, camera start to walk, and when
        //pulled again, it stops working.the walking direction is follow the center where user looks at
        if (isWalking) {
            isWalking = false;
            mOverlayView.show3DToast("STOP!");
        }
        else{
            isWalking = true;
            mUpdateHeadDir = true;
            mOverlayView.show3DToast("Let's walk around!");
            //find where the user looks at, and this will be the next walking direction
        }
        // Always give user feedback
        mVibrator.vibrate(50);
    }

    /**
     * Find a new random position for the object.
     * We'll rotate it around the Y-axis so it's out of sight, and then up or down by a little bit.
     */
    private void hideObject() {
        float[] rotationMatrix = new float[16];
        float[] posVec = new float[4];

        // First rotate in XZ plane, between 90 and 270 deg away, and scale so that we vary
        // the object's distance from the user.
        float angleXZ = (float) Math.random() * 180 + 90;
        Matrix.setRotateM(rotationMatrix, 0, angleXZ, 0f, 1f, 0f);
        float oldObjectDistance = mObjectDistance;
        mObjectDistance = (float) Math.random() * 15 + 5;
        float objectScalingFactor = mObjectDistance / oldObjectDistance;
        Matrix.scaleM(rotationMatrix, 0, objectScalingFactor, objectScalingFactor, objectScalingFactor);
        Matrix.multiplyMV(posVec, 0, rotationMatrix, 0, mModelCube, 12);

        // Now get the up or down angle, between -20 and 20 degrees
      /*  float angleY = (float) Math.random() * 80 - 40; // angle in Y plane, between -40 and 40
        angleY = (float) Math.toRadians(angleY);
        float newY = (float)Math.tan(angleY) * mObjectDistance;*/

        Matrix.setIdentityM(mModelCube, 0);
     //   Matrix.translateM(mModelCube, 0, posVec[0], -17f, posVec[2]);
       // Matrix.translateM(mModelCube, 0, posVec[0], newY, posVec[2]);
        Matrix.translateM(mModelCube, 0, posVec[0], CAMERA_Y, posVec[2]);
    }

    /**
     * Check if user is looking at object by calculating where the object is in eye-space.
     * @return
     */
    private boolean isLookingAtObject() {
        float[] initVec = {0, 0, 1.0f, 1.0f};
        float[] objPositionVec = new float[4];

        // Convert object space to camera space. Use the headView from onNewFrame.
        Matrix.multiplyMM(mModelView, 0, mHeadView, 0, mModelCube, 0);
        Matrix.multiplyMV(objPositionVec, 0, mModelView, 0, initVec, 0);

        float pitch = (float)Math.atan2(objPositionVec[1], -objPositionVec[2]);
        float yaw = (float)Math.atan2(objPositionVec[0], -objPositionVec[2]);

        Log.i(TAG, "Object position: X: " + objPositionVec[0]
                + "  Y: " + objPositionVec[1] + " Z: " + objPositionVec[2]);
        Log.i(TAG, "Object Pitch: " + pitch +"  Yaw: " + yaw);

        return (Math.abs(pitch) < PITCH_LIMIT) && (Math.abs(yaw) < YAW_LIMIT);
    }
}
