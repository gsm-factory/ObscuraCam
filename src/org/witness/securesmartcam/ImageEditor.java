package org.witness.securesmartcam;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

import org.witness.informa.InformaEditor;
import org.witness.informa.utils.MetadataParser;
import org.witness.securesmartcam.detect.GoogleFaceDetection;
import org.witness.securesmartcam.filters.MaskObscure;
import org.witness.securesmartcam.filters.RegionProcesser;
import org.witness.securesmartcam.jpegredaction.JpegRedaction;
import org.witness.sscphase1.ObscuraApp;
import org.witness.sscphase1.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Images.Media;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

public class ImageEditor extends Activity implements OnTouchListener, OnClickListener, OnLongClickListener {


	public final static String MIME_TYPE_JPEG = "image/jpeg";
	
	// Colors for region squares
	
	public final static int DRAW_COLOR = 0x00000000;
	public final static int DETECTED_COLOR = 0x00000000;
	public final static int OBSCURED_COLOR = 0x00000000;
	
	// Constants for the menu items, currently these are in an XML file (menu/image_editor_menu.xml, strings.xml)
	public final static int ABOUT_MENU_ITEM = 0;
	public final static int DELETE_ORIGINAL_MENU_ITEM = 1;
	public final static int SAVE_MENU_ITEM = 2;
	public final static int SHARE_MENU_ITEM = 3;
	public final static int NEW_REGION_MENU_ITEM = 4;
	
	// Constants for Informa
	public final static int FROM_INFORMA = 100;
	public final static String LOG = "[Image Editor ********************]";
	
	// Image Matrix
	Matrix matrix = new Matrix();

	// Saved Matrix for not allowing a current operation (over max zoom)
	Matrix savedMatrix = new Matrix();
		
	// We can be in one of these 3 states
	static final int NONE = 0;
	static final int DRAG = 1;
	static final int ZOOM = 2;
	static final int TAP = 3;
	int mode = NONE;

	
	// Maximum zoom scale
	static final float MAX_SCALE = 10f;
	
	// Constant for autodetection dialog
	static final int DIALOG_DO_AUTODETECTION = 0;
	
	// For Zooming
	float startFingerSpacing = 0f;
	float endFingerSpacing = 0f;
	PointF startFingerSpacingMidPoint = new PointF();
	
	// For Dragging
	PointF startPoint = new PointF();
	
	// Don't allow it to move until the finger moves more than this amount
	// Later in the code, the minMoveDistance in real pixels is calculated
	// to account for different touch screen resolutions
	float minMoveDistanceDP = 5f;
	float minMoveDistance; // = ViewConfiguration.get(this).getScaledTouchSlop();
	
	// zoom in and zoom out buttons
	Button zoomIn, zoomOut, btnSave, btnShare, btnPreview, btnNew;
	
	// ImageView for the original (scaled) image
	ImageView imageView;
	
		
	// Bitmap for the original image (scaled)
	Bitmap imageBitmap;
	
	// Bitmap for holding the realtime obscured image
    Bitmap obscuredBmp;
    
    // Canvas for drawing the realtime obscuring
    Canvas obscuredCanvas;
	
    // Paint obscured
    Paint obscuredPaint;
    
    //bitmaps for corners
    private final static float CORNER_SIZE = 26;
    Bitmap bitmapCornerUL;
    Bitmap bitmapCornerUR;
    Bitmap bitmapCornerLL;
    Bitmap bitmapCornerLR;
    
    
    
	// Vector to hold ImageRegions 
	Vector<ImageRegion> imageRegions = new Vector<ImageRegion>(); 
	MetadataParser mp;
		
	// The original image dimensions (not scaled)
	int originalImageWidth;
	int originalImageHeight;

	// So we can give some haptic feedback to the user
	Vibrator vibe;

	// Original Image Uri
	Uri originalImageUri;
	
	// sample sized used to downsize from native photo
	int inSampleSize;
	
	// Saved Image Uri
	Uri savedImageUri;
	
	// Constant for temp filename
	public final static String TMP_FILE_NAME = "tmp.jpg";
	
	public final static String TMP_FILE_DIRECTORY = "/Android/data/org.witness.sscphase1/files/";
	
	
	//handles threaded events for the UI thread
    private Handler mHandler = new Handler();

    //UI for background threads
    ProgressDialog mProgressDialog;
    
    // Handles when we should do realtime preview and when we shouldn't
    boolean doRealtimePreview = true;
    
    // Keep track of the orientation
    private int originalImageOrientation = ExifInterface.ORIENTATION_NORMAL;    

    private Runnable mUpdateTimeTask = new Runnable() {
    	   public void run() {
    		   doAutoDetection();
    	   }
    	};

	@SuppressWarnings("unused")
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
				

        String versNum = "";
        
        try {
            String pkg = getPackageName();
            versNum = getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (Exception e) {
        	versNum = "";
        }

        setTitle(getString(R.string.app_name) + " (" + versNum + ")");
        
        requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.imageviewer);

		// Calculate the minimum distance
		minMoveDistance = minMoveDistanceDP * this.getResources().getDisplayMetrics().density + 0.5f;
		
		// The ImageView that contains the image we are working with
		imageView = (ImageView) findViewById(R.id.ImageEditorImageView);

		// Buttons for zooming
		zoomIn = (Button) this.findViewById(R.id.ZoomIn);
		zoomOut = (Button) this.findViewById(R.id.ZoomOut);
		btnNew = (Button) this.findViewById(R.id.New);
		btnSave = (Button) this.findViewById(R.id.Save);
		btnShare = (Button) this.findViewById(R.id.Share);
		btnPreview = (Button) this.findViewById(R.id.Preview);
		
		// this, ImageEditor will be the onClickListener for the buttons
		zoomIn.setOnClickListener(this);
		zoomOut.setOnClickListener(this);
		btnNew.setOnClickListener(this);
		btnSave.setOnClickListener(this);
		btnShare.setOnClickListener(this);
		btnPreview.setOnClickListener(this);

		// Instantiate the vibrator
		vibe = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
		
		// Passed in from CameraObscuraMainMenu
		originalImageUri = getIntent().getData();
		
		// If originalImageUri is null, we are likely coming from another app via "share"
		if (originalImageUri == null)
		{
			if (getIntent().hasExtra(Intent.EXTRA_STREAM)) 
			{
				originalImageUri = (Uri) getIntent().getExtras().get(Intent.EXTRA_STREAM);
			}
			else if (getIntent().hasExtra("bitmap"))
			{
				Bitmap b = (Bitmap)getIntent().getExtras().get("bitmap");
				setBitmap(b, true);
				originalImageWidth = b.getWidth();
				originalImageHeight = b.getHeight();
				return;
				
			}
		}
		
		
		// Load the image if it isn't null
		if (originalImageUri != null) {
			
			// Get the orientation
			String originalFilename = pullPathFromUri(originalImageUri);			
			try {
				ExifInterface ei = new ExifInterface(originalFilename);
				originalImageOrientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
				debug(ObscuraApp.TAG,"Orientation: " + originalImageOrientation);
			} catch (IOException e1) {
				debug(ObscuraApp.TAG,"Couldn't get Orientation");
				e1.printStackTrace();
			}

			//debug(ObscuraApp.TAG,"loading uri: " + pullPathFromUri(originalImageUri));

			// Load up smaller image
			try {
				// Load up the image's dimensions not the image itself
				BitmapFactory.Options bmpFactoryOptions = new BitmapFactory.Options();
				bmpFactoryOptions.inJustDecodeBounds = true;
				// Needs to be this config for Google Face Detection 
				bmpFactoryOptions.inPreferredConfig = Bitmap.Config.RGB_565;
				// Parse the image
				Bitmap loadedBitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(originalImageUri), null, bmpFactoryOptions);

				// Hold onto the unscaled dimensions
				originalImageWidth = bmpFactoryOptions.outWidth;
				originalImageHeight = bmpFactoryOptions.outHeight;
				// If it is rotated, transpose the width and height
				// Should probably look to see if there are different rotation constants being used
				if (originalImageOrientation == ExifInterface.ORIENTATION_ROTATE_90 
						|| originalImageOrientation == ExifInterface.ORIENTATION_ROTATE_270) {
					int tmpWidth = originalImageWidth;
					originalImageWidth = originalImageHeight;
					originalImageHeight = tmpWidth;
				}

				// Get the current display to calculate ratios
				Display currentDisplay = getWindowManager().getDefaultDisplay();
				
				// Ratios between the display and the image
				double widthRatio =  Math.floor(bmpFactoryOptions.outWidth / currentDisplay.getWidth());
				double heightRatio = Math.floor(bmpFactoryOptions.outHeight / currentDisplay.getHeight());

				/*
				debug(ObscuraApp.TAG,"Display Width: " + currentDisplay.getWidth());
				debug(ObscuraApp.TAG,"Display Height: " + currentDisplay.getHeight());
				
				debug(ObscuraApp.TAG,"Image Width: " + originalImageWidth);
				debug(ObscuraApp.TAG,"Image Height: " + originalImageHeight);

				debug(ObscuraApp.TAG, "HEIGHTRATIO:" + heightRatio);
				debug(ObscuraApp.TAG, "WIDTHRATIO:" + widthRatio);
				 */
				
				// If both of the ratios are greater than 1,
				// one of the sides of the image is greater than the screen
				if (heightRatio > 1 && widthRatio > 1) {
					if (heightRatio > widthRatio) {
						// Height ratio is larger, scale according to it
						inSampleSize = (int)heightRatio;
					} else {
						// Width ratio is larger, scale according to it
						inSampleSize = (int)widthRatio;
					}
				}
				else
				{
					inSampleSize = 1;
				}
				
				bmpFactoryOptions.inSampleSize = inSampleSize;
		
				// Decode it for real
				bmpFactoryOptions.inJustDecodeBounds = false;
				loadedBitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(originalImageUri), null, bmpFactoryOptions);
				debug(ObscuraApp.TAG,"Was: " + loadedBitmap.getConfig());

				if (loadedBitmap == null) {
					debug(ObscuraApp.TAG,"bmp is null");
				
				}
				else
				{
					// Only dealing with 90 and 270 degree rotations, might need to check for others
					if (originalImageOrientation == ExifInterface.ORIENTATION_ROTATE_90) 
					{
						debug(ObscuraApp.TAG,"Rotating Bitmap 90");
						Matrix rotateMatrix = new Matrix();
						rotateMatrix.postRotate(90);
						loadedBitmap = Bitmap.createBitmap(loadedBitmap,0,0,loadedBitmap.getWidth(),loadedBitmap.getHeight(),rotateMatrix,false);
					}
					else if (originalImageOrientation == ExifInterface.ORIENTATION_ROTATE_270) 
					{
						debug(ObscuraApp.TAG,"Rotating Bitmap 270");
						Matrix rotateMatrix = new Matrix();
						rotateMatrix.postRotate(270);
						loadedBitmap = Bitmap.createBitmap(loadedBitmap,0,0,loadedBitmap.getWidth(),loadedBitmap.getHeight(),rotateMatrix,false);
					}

					setBitmap (loadedBitmap, true);
				}				
			} catch (IOException e) {
				Log.e(ObscuraApp.TAG, "error loading bitmap from Uri: " + e.getMessage(), e);
			}
			
			
			
		}
		
		bitmapCornerUL = BitmapFactory.decodeResource(getResources(),
                R.drawable.edit_region_corner_ul);
		bitmapCornerUR = BitmapFactory.decodeResource(getResources(),
                R.drawable.edit_region_corner_ur);
		bitmapCornerLL = BitmapFactory.decodeResource(getResources(),
                R.drawable.edit_region_corner_ll);
		bitmapCornerLR = BitmapFactory.decodeResource(getResources(),
                R.drawable.edit_region_corner_lr);
		 
	}
	
	private void setBitmap (Bitmap nBitmap, boolean autodetect)
	{
		imageBitmap = nBitmap;
		
		// Get the current display to calculate ratios
		Display currentDisplay = getWindowManager().getDefaultDisplay();

		float matrixWidthRatio = (float) currentDisplay.getWidth() / (float) imageBitmap.getWidth();
		float matrixHeightRatio = (float) currentDisplay.getHeight() / (float) imageBitmap.getHeight();

		// Setup the imageView and matrix for scaling
		float matrixScale = matrixHeightRatio;
		
		if (matrixWidthRatio < matrixHeightRatio) {
			matrixScale = matrixWidthRatio;
		} 
		
		imageView.setImageBitmap(imageBitmap);

		// Set the OnTouch and OnLongClick listeners to this (ImageEditor)
		imageView.setOnTouchListener(this);
		imageView.setOnClickListener(this);
		imageView.setOnLongClickListener(this);
		
		
		//PointF midpoint = new PointF((float)imageBitmap.getWidth()/2f, (float)imageBitmap.getHeight()/2f);
		matrix.postScale(matrixScale, matrixScale);

		// This doesn't completely center the image but it get's closer
		int fudge = 42;
		matrix.postTranslate((float)((float)currentDisplay.getWidth()-(float)imageBitmap.getWidth()*(float)matrixScale)/2f,(float)((float)currentDisplay.getHeight()-(float)imageBitmap.getHeight()*matrixScale)/2f-fudge);
		
		imageView.setImageMatrix(matrix);
		
		
		if (autodetect)
		{
		// Do auto detect popup

		Toast autodetectedToast = Toast.makeText(this, "Detecting faces...", Toast.LENGTH_SHORT);
		autodetectedToast.show();
		mHandler.postDelayed(mUpdateTimeTask, 1000);
		}
	}
	/*
	 * Call this to delete the original image, will ask the user
	 */
	private void showDeleteOriginalDialog() 
	{
		final AlertDialog.Builder b = new AlertDialog.Builder(this);
		b.setIcon(android.R.drawable.ic_dialog_alert);
		b.setTitle(getString(R.string.app_name));
		b.setMessage(getString(R.string.confirm_delete));
		b.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {

            	try
            	{
	                // User clicked OK so go ahead and delete
	        		deleteOriginal();
	            	viewImage(savedImageUri);
            	}
            	catch (IOException e)
            	{
            		Log.e(ObscuraApp.TAG, "error saving", e);
            	}
            	finally
            	{
            		finish();
            	}
            }
        });
		b.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {

            	viewImage(savedImageUri);
            }
        });
		b.show();
	}
	
	/*
	 * Actual deletion of original
	 */
	private void deleteOriginal() throws IOException
	{
		
		if (originalImageUri != null)
		{
			if (originalImageUri.getScheme().equals("file"))
			{
				String origFilePath = originalImageUri.getPath();
				File fileOrig = new File(origFilePath);

				String[] columnsToSelect = { MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA };
				
				/*
				ExifInterface ei = new ExifInterface(origFilePath);
				long dateTaken = new Date(ei.getAttribute(ExifInterface.TAG_DATETIME)).getTime();
				*/
				
				Uri[] uriBases = {MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.INTERNAL_CONTENT_URI};
				
				for (Uri uriBase : uriBases)
				{
					
			    	Cursor imageCursor = getContentResolver().query(uriBase, columnsToSelect, MediaStore.Images.Media.DATA + " = ?",  new String[] {origFilePath}, null );
					//Cursor imageCursor = getContentResolver().query(uriBase, columnsToSelect, MediaStore.Images.Media.DATE_TAKEN + " = ?",  new String[] {dateTaken+""}, null );
					
			        while (imageCursor.moveToNext())
			        {
			        
				       long _id = imageCursor.getLong(imageCursor.getColumnIndex(MediaStore.Images.Media._ID));
				    	   
				       getContentResolver().delete(ContentUris.withAppendedId(uriBase, _id), null, null);
				       
			    	}
				}
				
				if (fileOrig.exists())
					fileOrig.delete();
				
			}
			else
			{
				getContentResolver().delete(originalImageUri, null, null);
			}
		}
		
		originalImageUri = null;
	}
	
	
	/*
	 * Do actual auto detection and create regions
	 * 
	 * public void createImageRegion(int _scaledStartX, int _scaledStartY, 
			int _scaledEndX, int _scaledEndY, 
			int _scaledImageWidth, int _scaledImageHeight, 
			int _imageWidth, int _imageHeight, 
			int _backgroundColor) {
	 */
	
	private void doAutoDetection() {
		// This should be called via a pop-up/alert mechanism
		
		RectF[] autodetectedRects = runFaceDetection();
		for (int adr = 0; adr < autodetectedRects.length; adr++) {

			//debug(ObscuraApp.TAG,"AUTODETECTED imageView Width, Height: " + imageView.getWidth() + " " + imageView.getHeight());
			//debug(ObscuraApp.TAG,"UNSCALED RECT:" + autodetectedRects[adr].left + " " + autodetectedRects[adr].top + " " + autodetectedRects[adr].right + " " + autodetectedRects[adr].bottom);
			
			RectF autodetectedRectScaled = new RectF(autodetectedRects[adr].left, autodetectedRects[adr].top, autodetectedRects[adr].right, autodetectedRects[adr].bottom);
			
			//debug(ObscuraApp.TAG,"SCALED RECT:" + autodetectedRectScaled.left + " " + autodetectedRectScaled.top + " " + autodetectedRectScaled.right + " " + autodetectedRectScaled.bottom);

			// Probably need to map autodetectedRects to scaled rects
		//debug(ObscuraApp.TAG,"MAPPED RECT:" + autodetectedRects[adr].left + " " + autodetectedRects[adr].top + " " + autodetectedRects[adr].right + " " + autodetectedRects[adr].bottom);
			
			float faceBuffer = (autodetectedRectScaled.right-autodetectedRectScaled.left)/5;
			
			boolean showPopup = false;
			if (adr == autodetectedRects.length - 1) {
				showPopup = true;
			}
			createImageRegion(
					(autodetectedRectScaled.left-faceBuffer),
					(autodetectedRectScaled.top-faceBuffer),
					(autodetectedRectScaled.right+faceBuffer),
					(autodetectedRectScaled.bottom+faceBuffer),
					showPopup);
		}	
			
		
		Toast autodetectedToast = Toast.makeText(this, "" + autodetectedRects.length + " faces detected", Toast.LENGTH_SHORT);
		autodetectedToast.show();
	}
	
	/*
	 * The actual face detection calling method
	 */
	private RectF[] runFaceDetection() {
		RectF[] possibleFaceRects;
		
		try {
			GoogleFaceDetection gfd = new GoogleFaceDetection(imageBitmap);
			int numFaces = gfd.findFaces();
	        debug(ObscuraApp.TAG,"Num Faces Found: " + numFaces); 
	        possibleFaceRects = gfd.getFaces();
		} catch(NullPointerException e) {
			possibleFaceRects = null;
		}
		return possibleFaceRects;				
	}
	

	ImageRegion currRegion = null;
	
	/*
	 * Handles touches on ImageView
	 */
	@Override
	public boolean onTouch(View v, MotionEvent event) 
	{
		if (currRegion != null && (mode == DRAG || currRegion.getBounds().contains(event.getX(), event.getY())))		
			return onTouchRegion(v, event, currRegion);	
		else
			return onTouchImage(v,event);
	}
	
	public ImageRegion findRegion (MotionEvent event)
	{
		ImageRegion result = null;
		Matrix iMatrix = new Matrix();
		matrix.invert(iMatrix);

		float[] points = {event.getX(), event.getY()};        	
    	iMatrix.mapPoints(points);
    	
		for (ImageRegion region : imageRegions)
		{

			if (region.getBounds().contains(points[0],points[1]))
			{
				result = region;
				
				break;
			}
			
		}
	
		
		return result;
	}
	
	public boolean onTouchRegion (View v, MotionEvent event, ImageRegion iRegion)
	{
		boolean handled = false;
		
		currRegion.setMatrix(matrix);
		
		switch (event.getAction() & MotionEvent.ACTION_MASK) {
			case MotionEvent.ACTION_DOWN:
				clearImageRegionsEditMode();
				currRegion.setSelected(true);	
				
				currRegion.setCornerMode(event.getX(),event.getY());
				
				mode = DRAG;
				handled = iRegion.onTouch(v, event);

			break;
			
			case MotionEvent.ACTION_UP:
				mode = NONE;
				handled = iRegion.onTouch(v, event);
				currRegion.setSelected(false);
				if (handled)
					currRegion = null;
			
			break;
			
			default:
				mode = DRAG;
				handled = iRegion.onTouch(v, event);
			
		}
		
		return handled;
		
		
	}
	
	public boolean onTouchImage(View v, MotionEvent event) 
	{
		boolean handled = false;
		
		switch (event.getAction() & MotionEvent.ACTION_MASK) {
			case MotionEvent.ACTION_DOWN:
				// Single Finger down
				mode = TAP;				
				currRegion = findRegion(event);
				
				if (currRegion != null)
				{
					return onTouchRegion(v,  event, currRegion);
				}
				else
				{
					// 	Save the Start point. 
					startPoint.set(event.getX(), event.getY());
				}
				
				
				break;
				
			case MotionEvent.ACTION_POINTER_DOWN:
				// Two Fingers down

				// Don't do realtime preview while touching screen
				//doRealtimePreview = false;
				//updateDisplayImage();

				// Get the spacing of the fingers, 2 fingers
				float sx = event.getX(0) - event.getX(1);
				float sy = event.getY(0) - event.getY(1);
				startFingerSpacing = (float) Math.sqrt(sx * sx + sy * sy);

				//Log.d(ObscuraApp.TAG, "Start Finger Spacing=" + startFingerSpacing);
				
				// Get the midpoint
				float xsum = event.getX(0) + event.getX(1);
				float ysum = event.getY(0) + event.getY(1);
				startFingerSpacingMidPoint.set(xsum / 2, ysum / 2);
				
				mode = ZOOM;
				//Log.d(ObscuraApp.TAG, "mode=ZOOM");
				
				clearImageRegionsEditMode();
				
				break;
				
			case MotionEvent.ACTION_UP:
				// Single Finger Up
				
			    // Re-enable realtime preview
				doRealtimePreview = true;
				updateDisplayImage();
				
				//debug(ObscuraApp.TAG,"mode=NONE");

				break;
				
			case MotionEvent.ACTION_POINTER_UP:
				// Multiple Finger Up
				
			    // Re-enable realtime preview
				doRealtimePreview = true;
				updateDisplayImage();
				
				//Log.d(ObscuraApp.TAG, "mode=NONE");
				break;
				
			case MotionEvent.ACTION_MOVE:
				
				// Calculate distance moved
				float distance = (float) (Math.sqrt(Math.abs(startPoint.x - event.getX()) + Math.abs(startPoint.y - event.getY())));
				//debug(ObscuraApp.TAG,"Move Distance: " + distance);
				//debug(ObscuraApp.TAG,"Min Distance: " + minMoveDistance);
				
				// If greater than minMoveDistance, it is likely a drag or zoom
				if (distance > minMoveDistance) {
				
					if (mode == TAP || mode == DRAG) {
						mode = DRAG;
						
						matrix.postTranslate(event.getX() - startPoint.x, event.getY() - startPoint.y);
						imageView.setImageMatrix(matrix);
//						// Reset the start point
						startPoint.set(event.getX(), event.getY());
	
						putOnScreen();
						//redrawRegions();
						
						handled = true;
	
					} else if (mode == ZOOM) {
						
						// Save the current matrix so that if zoom goes to big, we can revert
						savedMatrix.set(matrix);
						
						// Get the spacing of the fingers, 2 fingers
						float ex = event.getX(0) - event.getX(1);
						float ey = event.getY(0) - event.getY(1);
						endFingerSpacing = (float) Math.sqrt(ex * ex + ey * ey);
	
						//Log.d(ObscuraApp.TAG, "End Finger Spacing=" + endFingerSpacing);
		
						// If we moved far enough
						if (endFingerSpacing > minMoveDistance) {
							
							// Ratio of spacing..  If it was 5 and goes to 10 the image is 2x as big
							float scale = endFingerSpacing / startFingerSpacing;
							// Scale from the midpoint
							matrix.postScale(scale, scale, startFingerSpacingMidPoint.x, startFingerSpacingMidPoint.y);
		
							// Make sure that the matrix isn't bigger than max scale/zoom
							float[] matrixValues = new float[9];
							matrix.getValues(matrixValues);
							/*
							debug(ObscuraApp.TAG, "Total Scale: " + matrixValues[0]);
							debug(ObscuraApp.TAG, "" + matrixValues[0] + " " + matrixValues[1]
									+ " " + matrixValues[2] + " " + matrixValues[3]
									+ " " + matrixValues[4] + " " + matrixValues[5]
									+ " " + matrixValues[6] + " " + matrixValues[7]
									+ " " + matrixValues[8]);
							*/
							// x = 1.5 * 1 + 0 * y + -120 * 1
							
							if (matrixValues[0] > MAX_SCALE) {
								matrix.set(savedMatrix);
							}
							imageView.setImageMatrix(matrix);
							
							putOnScreen();
							//redrawRegions();
	
							// Reset Start Finger Spacing
							float esx = event.getX(0) - event.getX(1);
							float esy = event.getY(0) - event.getY(1);
							startFingerSpacing = (float) Math.sqrt(esx * esx + esy * esy);
							//Log.d(ObscuraApp.TAG, "New Start Finger Spacing=" + startFingerSpacing);
							
							// Reset the midpoint
							float x_sum = event.getX(0) + event.getX(1);
							float y_sum = event.getY(0) + event.getY(1);
							startFingerSpacingMidPoint.set(x_sum / 2, y_sum / 2);
							
							handled = true;
						}
					}
				}
				break;
		}


		return handled; // indicate event was handled
	}

	/*
	public void moveAndZoom (float x, float y, float scale)
	{
		matrix.postTranslate(x - startPoint.x, y - startPoint.y);
		imageView.setImageMatrix(matrix);
//		// Reset the start point
		startPoint.set(x, y);
		
		matrix.postScale(scale, scale, startFingerSpacingMidPoint.x, startFingerSpacingMidPoint.y);
		
		imageView.setImageMatrix(matrix);
		
		putOnScreen();
		redrawRegions();
		
	}*/
	
	/*
	 * For live previews
	 */	
	public void updateDisplayImage()
	{
		if (doRealtimePreview) {
			imageView.setImageBitmap(createObscuredBitmap(imageBitmap.getWidth(),imageBitmap.getHeight(), true));
		} else {
			imageView.setImageBitmap(imageBitmap);
		}
	}
	
	/*
	 * Move the image onto the screen if it has been moved off
	 */
	public void putOnScreen() 
	{
		// Get Rectangle of Tranformed Image
		RectF theRect = getScaleOfImage();
		
		debug(ObscuraApp.TAG,theRect.width() + " " + theRect.height());
		
		float deltaX = 0, deltaY = 0;
		if (theRect.width() < imageView.getWidth()) {
			deltaX = (imageView.getWidth() - theRect.width())/2 - theRect.left;
		} else if (theRect.left > 0) {
			deltaX = -theRect.left;
		} else if (theRect.right < imageView.getWidth()) {
			deltaX = imageView.getWidth() - theRect.right;
		}		
		
		if (theRect.height() < imageView.getHeight()) {
			deltaY = (imageView.getHeight() - theRect.height())/2 - theRect.top;
		} else if (theRect.top > 0) {
			deltaY = -theRect.top;
		} else if (theRect.bottom < imageView.getHeight()) {
			deltaY = imageView.getHeight() - theRect.bottom;
		}
		
		//debug(ObscuraApp.TAG,"Deltas:" + deltaX + " " + deltaY);
		
		matrix.postTranslate(deltaX,deltaY);
		imageView.setImageMatrix(matrix);
		updateDisplayImage();
		
	}
	
	/* 
	 * Put all regions into normal mode, out of edit mode
	 */
	public void clearImageRegionsEditMode()
	{
		Iterator<ImageRegion> itRegions = imageRegions.iterator();
		
		while (itRegions.hasNext())
		{
			itRegions.next().setSelected(false);
		}
	}
	
	/*
	 * Create new ImageRegion
	 */
	public void createImageRegion(float left, float top, float right, float bottom, boolean showPopup) {
		
		clearImageRegionsEditMode();
		
		ImageRegion imageRegion = new ImageRegion(
				this, 
				left, 
				top, 
				right, 
				bottom,
				matrix);

		imageRegions.add(imageRegion);
		putOnScreen();
	}
	
	/*
	 * Delete/Remove specific ImageRegion
	 */
	public void deleteRegion(ImageRegion ir)
	{
		imageRegions.remove(ir);
		//redrawRegions();
		updateDisplayImage();
	}
	
	/*
	 * Returns the Rectangle of Tranformed Image
	 */
	public RectF getScaleOfImage() 
	{
		RectF theRect = new RectF(0,0,imageBitmap.getWidth(), imageBitmap.getHeight());
		matrix.mapRect(theRect);
		return theRect;
	}

	
	/*
	 * Handles normal onClicks for buttons registered to this.
	 * Currently only the zoomIn and zoomOut buttons
	 */
	@Override
	public void onClick(View v) {
		
		if (currRegion != null)
		{
			currRegion.inflatePopup(false);
			currRegion = null;
		}			
		else if (v == zoomIn) 
		{
			float scale = 1.5f;
			
			PointF midpoint = new PointF(imageView.getWidth()/2, imageView.getHeight()/2);
			matrix.postScale(scale, scale, midpoint.x, midpoint.y);
			imageView.setImageMatrix(matrix);
			putOnScreen();
		} 
		else if (v == zoomOut) 
		{
			float scale = 0.75f;

			PointF midpoint = new PointF(imageView.getWidth()/2, imageView.getHeight()/2);
			matrix.postScale(scale, scale, midpoint.x, midpoint.y);
			imageView.setImageMatrix(matrix);
			
			putOnScreen();
		} 
		else if (v == btnNew)
		{
			newDefaultRegion();
		}
		else if (v == btnPreview)
		{
			showPreview();
		}
		else if (v == btnSave)
		{
			//Why does this not show?
	    	mProgressDialog = ProgressDialog.show(this, "", "Saving...", true, true);

    		mHandler.postDelayed(new Runnable() {
    			  @Override
    			  public void run() {
    			    // this will be done in the Pipeline Thread
    	        		saveImage();
    			  }
    			},500);
		}
		else if (v == btnShare)
		{
			// Share Image
      		shareImage();
		}
		else if (mode != DRAG && mode != ZOOM) 
		{
			float defaultSize = imageView.getWidth()/4;
			float halfSize = defaultSize/2;
			
			RectF newBox = new RectF();
			
			newBox.left = startPoint.x - halfSize;
			newBox.top = startPoint.y - halfSize;

			newBox.right = startPoint.x + halfSize;
			newBox.bottom = startPoint.y + halfSize;
			
			Matrix iMatrix = new Matrix();
			matrix.invert(iMatrix);
			iMatrix.mapRect(newBox);
						
			createImageRegion(newBox.left, newBox.top, newBox.right, newBox.bottom, true);
		}
		
	}
	
	
	// Long Clicks create new image regions
	@Override
	public boolean onLongClick (View v)
	{
		/*
		if (mode != DRAG && mode != ZOOM) 
		{
			vibe.vibrate(50);

			float defaultSize = imageView.getWidth()/4;
			float halfSize = defaultSize/2;
			
			RectF newBox = new RectF();
			
			newBox.left = startPoint.x - halfSize;
			newBox.top = startPoint.y - halfSize;

			newBox.right = startPoint.x + halfSize;
			newBox.bottom = startPoint.y + halfSize;
			
			float[] mValues = new float[9];
			matrix.getValues(mValues);
			float scaleX = mValues[Matrix.MSCALE_X];
			float scaleY = mValues[Matrix.MSCALE_Y];
			
			float leftOffset = mValues[Matrix.MTRANS_X];
			float topOffset = mValues[Matrix.MTRANS_Y];
			
			newBox.left = (newBox.left / scaleX) - leftOffset;
			newBox.top = (newBox.top / scaleY) - topOffset;
			newBox.right = (newBox.right / scaleX) - leftOffset;
			newBox.bottom = (newBox.bottom / scaleY) - topOffset;

			createImageRegion(newBox.left, newBox.top, newBox.right, newBox.bottom, true);
			return true;
		}
		*/
		return true;
	}
	
	/*
	 * Standard method for menu items.  Uses res/menu/image_editor_menu.xml
	 */
	@Override
    public boolean onCreateOptionsMenu(Menu menu) {

    	MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.image_editor_menu, menu);

        return true;
    }
	
	private void newDefaultRegion ()
	{
		// Set the Start point. 
		startPoint.set(imageView.getWidth()/2, imageView.getHeight()/2);
		
		float defaultSize = imageView.getWidth()/4;
		float halfSize = defaultSize/2;
		
		RectF newRegion = new RectF();
		
		newRegion.left = startPoint.x - halfSize;
		newRegion.top = startPoint.y - halfSize;

		newRegion.right =  startPoint.x + defaultSize;
		newRegion.left =  startPoint.y + defaultSize;
		
		Matrix iMatrix = new Matrix();
		matrix.invert(iMatrix);
		iMatrix.mapRect(newRegion);
		
		createImageRegion(newRegion.left,newRegion.top,newRegion.right,newRegion.bottom, false);
		
	}
    /*
     * Normal menu item selected method.  Uses menu items defined in XML: res/menu/image_editor_menu.xml
     */
	@Override
    public boolean onOptionsItemSelected(MenuItem item) {

    	switch (item.getItemId()) {
    	
    		case R.id.menu_new_region:
    			
    			newDefaultRegion();

    			return true;
    			
        	case R.id.menu_save:

				//Why does this not show?
		    	mProgressDialog = ProgressDialog.show(this, "", "Saving...", true, true);
	
        		mHandler.postDelayed(new Runnable() {
        			  @Override
        			  public void run() {
        			    // this will be done in the Pipeline Thread
        	        		saveImage();
        			  }
        			},500);

        		
        		return true;
        		
        	case R.id.menu_share:
        		// Share Image
          		shareImage();

        		
        		return true;
        	
/*
 			case R.id.menu_delete_original:
        		// Delete Original Image
        		handleDelete();
        		
        		return true;
*/        		
        	case R.id.menu_about:
        		// Pull up about screen
        		displayAbout();
        		
        		return true;
        	
        	case R.id.menu_preview:
        		showPreview();
        		
        		return true;
        		
    		default:
    			return false;
    	}
    }
    	
	/*
	 * Display the about screen
	 */
	private void displayAbout() {
		
		StringBuffer msg = new StringBuffer();
		
		msg.append(getString(R.string.app_name));
		
        String versNum = "";
        
        try {
            String pkg = getPackageName();
            versNum = getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (Exception e) {
        	versNum = "";
        }
        
        msg.append(" v" + versNum);
        msg.append('\n');
        msg.append('\n');
        
        msg.append(getString(R.string.about));
	        
        msg.append('\n');
        msg.append('\n');
        
        msg.append(getString(R.string.about2));
        
        msg.append('\n');
        msg.append('\n');
        
        msg.append(getString(R.string.about3));
        
		showDialog(msg.toString());
	}
	
	private void showDialog (String msg)
	{
		 new AlertDialog.Builder(this)
         .setTitle(getString(R.string.app_name))
         .setMessage(msg)
         .create().show();
	}
	
	/*
	 * Display preview image
	 */
	private void showPreview() {
		
		// Open Preview Activity
		Uri tmpImageUri = saveTmpImage();
		
		if (tmpImageUri != null)
		{
			Intent intent = new Intent(this, ImagePreview.class);
			intent.putExtra(ImagePreview.IMAGEURI, tmpImageUri.toString());
			startActivity(intent);				
		}
	}
	
	/*
	 * When the user selects the Share menu item
	 * Uses saveTmpImage (overwriting what is already there) and uses the standard Android Share Intent
	 */
    private void shareImage() {
    	Uri tmpImageUri;
    	
    	if ((tmpImageUri = saveTmpImage()) != null) {
        	Intent share = new Intent(Intent.ACTION_SEND);
        	share.setType("image/jpeg");
        	share.putExtra(Intent.EXTRA_STREAM, tmpImageUri);
        	startActivity(Intent.createChooser(share, "Share Image"));    	
    	} else {
    		Toast t = Toast.makeText(this,"Saving Temporary File Failed!", Toast.LENGTH_SHORT); 
    		t.show();
    	}
    }
    
	/*
	 * When the user selects the Share menu item
	 * Uses saveTmpImage (overwriting what is already there) and uses the standard Android Share Intent
	 */
    private void viewImage(Uri imgView) {
    	
    	Intent iView = new Intent(Intent.ACTION_VIEW);
    	iView.setType(MIME_TYPE_JPEG);
    	iView.putExtra(Intent.EXTRA_STREAM, imgView);
    	iView.setDataAndType(imgView, MIME_TYPE_JPEG);

    	startActivity(Intent.createChooser(iView, "View Image"));    	
	
    }
    
    
    /*
     * Goes through the regions that have been defined and creates a bitmap with them obscured.
     * This may introduce memory issues and therefore have to be done in a different manner.
     */
    private Bitmap createObscuredBitmap(int width, int height, boolean showBorders) 
    {
    	if (imageBitmap == null)
    		return null;
    	
    	if (obscuredBmp == null || (obscuredBmp.getWidth() != width))
    	{
    		// Create the bitmap that we'll output from this method
    		obscuredBmp = Bitmap.createBitmap(width, height,imageBitmap.getConfig());
    	
    		// Create the canvas to draw on
    		obscuredCanvas = new Canvas(obscuredBmp); 
    	}
    	
    	// Create the paint used to draw with
    	obscuredPaint = new Paint();   
    	// Create a default matrix
    	Matrix obscuredMatrix = new Matrix();    	
    	// Draw the scaled image on the new bitmap
    	obscuredCanvas.drawBitmap(imageBitmap, obscuredMatrix, obscuredPaint);

    	// Iterate through the regions that have been created
    	Iterator<ImageRegion> i = imageRegions.iterator();
	    while (i.hasNext()) 
	    {
	    	ImageRegion currentRegion = i.next();
	    	RegionProcesser om = currentRegion.getRegionProcessor();

            RectF regionRect = new RectF(currentRegion.getBounds());
	    	om.processRegion(regionRect, obscuredCanvas, obscuredBmp);

	    	if (showBorders)
	    	{
		    	if (currentRegion.isSelected())
		    		obscuredPaint.setColor(Color.GREEN);
		    	else
		    		obscuredPaint.setColor(Color.WHITE);
		    	
		    	obscuredPaint.setStyle(Style.STROKE);
		    	obscuredPaint.setStrokeWidth(10f);
		    	obscuredCanvas.drawRect(regionRect, obscuredPaint);
		    	
		    	if (currentRegion.isSelected())
		    	{
		    		obscuredCanvas.drawBitmap(bitmapCornerUL, regionRect.left-CORNER_SIZE, regionRect.top-CORNER_SIZE, obscuredPaint);
		    		obscuredCanvas.drawBitmap(bitmapCornerLL, regionRect.left-CORNER_SIZE, regionRect.bottom-(CORNER_SIZE/2), obscuredPaint);
		    		obscuredCanvas.drawBitmap(bitmapCornerUR, regionRect.right-(CORNER_SIZE/2), regionRect.top-CORNER_SIZE, obscuredPaint);
		    		obscuredCanvas.drawBitmap(bitmapCornerLR, regionRect.right-(CORNER_SIZE/2), regionRect.bottom-(CORNER_SIZE/2), obscuredPaint);

		    	}
		    	
	    	}
		}

	    return obscuredBmp;
    }
    
    
    private boolean canDoNative ()
    {
    	if (originalImageUri == null)
    		return false;
    				
    	// Iterate through the regions that have been created
    	Iterator<ImageRegion> i = imageRegions.iterator();
	    while (i.hasNext()) 
	    {
	    	ImageRegion iRegion = i.next();
	    	if (iRegion.getRegionProcessor() instanceof MaskObscure)
	    		return false;
	    }
	    
	    return true;

    }
    
    /*
     * Goes through the regions that have been defined and creates a bitmap with them obscured.
     * This may introduce memory issues and therefore have to be done in a different manner.
     */
    private Uri processNativeRes (Uri sourceImage) throws Exception
    {
    	// Create the Uri - This can't be "private"
    	/*
    	File tmpFileDirectory = new File(Environment.getExternalStorageDirectory(), TMP_FILE_DIRECTORY);
    
    	if (!tmpFileDirectory.exists()) {
    		tmpFileDirectory.mkdirs();
    	}*/

    	File tmpFileDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES); 
    	if (!tmpFileDirectory.exists()) {
    		tmpFileDirectory.mkdirs();
    	}
    	File tmpInFile = new File(tmpFileDirectory,TMP_FILE_NAME);
    	
    	Uri tmpImageUri = Uri.fromFile(tmpInFile);
		copy (sourceImage, tmpImageUri);
	  	
		JpegRedaction om = new JpegRedaction();	
    	om.setFiles(tmpInFile, tmpInFile);
	
		// Iterate through the regions that have been created
    	Iterator<ImageRegion> i = imageRegions.iterator();
	    while (i.hasNext()) 
	    {
	    	
	    	ImageRegion currentRegion = i.next();
	  
	    	
	    	om.setMethod(currentRegion.getRegionProcessor());
            RectF regionRect = new RectF(currentRegion.getBounds());
            regionRect.left *= inSampleSize;
            regionRect.top *= inSampleSize;
            regionRect.right *= inSampleSize;
            regionRect.bottom *= inSampleSize;
            
	    	om.processRegion(regionRect, obscuredCanvas, obscuredBmp);
		
	    }
	    	    
	    return  Uri.fromFile(tmpInFile);
    }
    
    private void copy (Uri uriSrc, Uri uriTarget) throws IOException
    {
    	
    	InputStream is = getContentResolver().openInputStream(uriSrc);
		
		OutputStream os = getContentResolver().openOutputStream(uriTarget);
			
		byte buffer[] = new byte[4096];
		int i;
		
		while ((i = is.read(buffer))!=-1)
		{
			os.write(buffer, 0, i);
		}
		
		os.close();
		is.close();

    	
    }
    /*
     * Save a temporary image for sharing only
     */
    private Uri saveTmpImage() {
    	
    	String storageState = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(storageState)) {
        	Toast t = Toast.makeText(this,"External storage not available", Toast.LENGTH_SHORT); 
    		t.show();
    		return null;
    	}
    	
    	// Create the bitmap that will be saved
    	// Perhaps this should be smaller than screen size??
    	int w = imageBitmap.getWidth();
    	int h = imageBitmap.getHeight();
    	Bitmap obscuredBmp = createObscuredBitmap(w,h, false);
    	
    	// Create the Uri - This can't be "private"
    	File tmpFileDirectory = new File(Environment.getExternalStorageDirectory().getPath() + TMP_FILE_DIRECTORY);
    	File tmpFile = new File(tmpFileDirectory,TMP_FILE_NAME);
    	debug(ObscuraApp.TAG, tmpFile.getPath());
    	
		try {
	    	if (!tmpFileDirectory.exists()) {
	    		tmpFileDirectory.mkdirs();
	    	}
	    	Uri tmpImageUri = Uri.fromFile(tmpFile);
	    	
			OutputStream imageFileOS;

			int quality = 75;
			imageFileOS = getContentResolver().openOutputStream(tmpImageUri);
			obscuredBmp.compress(CompressFormat.JPEG, quality, imageFileOS);

			return tmpImageUri;
		} catch (FileNotFoundException e) {
			mProgressDialog.cancel();
			e.printStackTrace();
			return null;
		}
    }
    
    /*
     * The method that actually saves the altered image.  
     * This in combination with createObscuredBitmap could/should be done in another, more memory efficient manner. 
     */
    private boolean saveImage() 
    {

    	ContentValues cv = new ContentValues();
    	
    	// Add a date so it shows up in a reasonable place in the gallery - Should we do this??
    	SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); 
    	Date date = new Date();

		// Which one?
    	cv.put(Images.Media.DATE_ADDED, dateFormat.format(date));
    	cv.put(Images.Media.DATE_TAKEN, dateFormat.format(date));
    	cv.put(Images.Media.DATE_MODIFIED, dateFormat.format(date));
    	cv.put(Images.Media.TITLE, dateFormat.format(date));
    //    cv.put(Images.Media.BUCKET_ID, "ObscuraCam");
    //    cv.put(Images.Media.DESCRIPTION, "ObscuraCam");
    	//cv.put(Images.Media.CONTENT_TYPE, MIME_TYPE_JPEG);

    	// Uri is savedImageUri which is global
    	// Create the Uri, this should put it in the gallery
    	// New Each time
		savedImageUri = getContentResolver().insert(
				Media.EXTERNAL_CONTENT_URI, cv);
		
		if (savedImageUri == null)
			return false;
		
		boolean nativeSuccess = false;
		
    	if (canDoNative())
    	{
    		try {
    			Uri savedNativeTmp = processNativeRes(originalImageUri);

    			copy(savedNativeTmp, savedImageUri);
    			
    			nativeSuccess = true;
    			
    		} catch (Exception e) {
    			Log.e(ObscuraApp.TAG, "error doing native redact",e);
    		}
    	}
    	
    	
    	if (!nativeSuccess)
    	{
    		try {
    			
    			obscuredBmp = createObscuredBitmap(imageBitmap.getWidth(),imageBitmap.getHeight(), false);
    
    			OutputStream imageFileOS;
			
				int quality = 100; //lossless?  good question - still a smaller version
				imageFileOS = getContentResolver().openOutputStream(savedImageUri);
				obscuredBmp.compress(CompressFormat.JPEG, quality, imageFileOS);
				
			} catch (Exception e) {
				Log.e(ObscuraApp.TAG, "error doing redact",e);
				return false;
			}

    	}

		// package and insert exif data
		mp = new MetadataParser(dateFormat.format(date), new File(pullPathFromUri(savedImageUri)), this);
		Iterator<ImageRegion> i = imageRegions.iterator();
	    while (i.hasNext()) {
	    	mp.addRegion(i.next().getRegionProcessor().getProperties());
	    }

		mp.flushMetadata();

		// force mediascanner to update file
		MediaScannerConnection.scanFile(
				this,
				new String[] {pullPathFromUri(savedImageUri)},
				new String[] {MIME_TYPE_JPEG},
				null);
		
		Toast t = Toast.makeText(this,"Image saved to Gallery", Toast.LENGTH_SHORT); 
		t.show();

		mProgressDialog.cancel();
		
		showDeleteOriginalDialog ();
		
		
		return true;
    }
    
    // Queries the contentResolver to pull out the path for the actual file.
    /*  This code is currently unused but i often find myself needing it so I 
     * am placing it here for safe keeping ;-) */
    
    /*
     * Yep, uncommenting it back out so we can use the original path to refresh media scanner
     * HNH 8/23/11
     */
    public String pullPathFromUri(Uri originalUri) {

    	String originalImageFilePath = null;

    	if (originalUri.getScheme() != null && originalUri.getScheme().equals("file"))
    	{
    		originalImageFilePath = originalUri.toString();
    	}
    	else
    	{
	    	String[] columnsToSelect = { MediaStore.Images.Media.DATA };
	    	Cursor imageCursor = getContentResolver().query(originalUri, columnsToSelect, null, null, null );
	    	if ( imageCursor != null && imageCursor.getCount() == 1 ) {
		        imageCursor.moveToFirst();
		        originalImageFilePath = imageCursor.getString(imageCursor.getColumnIndex(MediaStore.Images.Media.DATA));
	    	}
    	}

    	return originalImageFilePath;
    }

    /*
     * Handling screen configuration changes ourselves, we don't want the activity to restart on rotation
     */
    @Override
    public void onConfigurationChanged(Configuration conf) 
    {
        super.onConfigurationChanged(conf);
        
        recenterImage();
    }
    
    private void recenterImage ()
    {
    	/*
        matrix.postTranslate(0,0);
		imageView.setImageMatrix(matrix);
//		// Reset the start point
		startPoint.set(0,0);
		*/
    	float scale = 1.2f;		
		PointF midpoint = new PointF(imageView.getWidth()/2, imageView.getHeight()/2);
		matrix.postScale(scale, scale, midpoint.x, midpoint.y);
		imageView.setImageMatrix(matrix);
		putOnScreen();
		
		scale = 1f;		
		midpoint = new PointF(imageView.getWidth()/2, imageView.getHeight()/2);
		matrix.postScale(scale, scale, midpoint.x, midpoint.y);
		imageView.setImageMatrix(matrix);
		putOnScreen();
		
    }
    
    public void launchInforma(ImageRegion ir) {
    	Intent informa = new Intent(this,InformaEditor.class);
    	informa.putExtra("mProps", ir.getRegionProcessor().getProperties());
    	informa.putExtra("irIndex", imageRegions.indexOf(ir));
    	startActivityForResult(informa,FROM_INFORMA);
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
    	if(resultCode == Activity.RESULT_OK) {
    		if(requestCode == FROM_INFORMA) {
    			// replace corresponding image region
    			@SuppressWarnings("unchecked")
				HashMap<String, String> informaReturn = (HashMap<String, String>) data.getSerializableExtra("informaReturn");    			
    			Properties mProp = imageRegions.get(data.getIntExtra("irIndex", 0)).getRegionProcessor().getProperties();
    			
    			// iterate through returned hashmap and place these new properties in it.
    			for(Map.Entry<String, String> entry : informaReturn.entrySet()) {
    				mProp.setProperty(entry.getKey(), entry.getValue());
    			}
    			
    			imageRegions.get(data.getIntExtra("irIndex", 0)).getRegionProcessor().setProperties(mProp);
    			    			
    		}
    	}
    }

	@Override
	protected void onPostResume() {
		super.onPostResume();
		
	}
	
	public Paint getPainter ()
	{
		return obscuredPaint;
	}
	
	private void debug (String tag, String message)
	{
		Log.d(tag, message);
	}
	

	public ImageView getImageView() {
		return imageView;
	}

}
