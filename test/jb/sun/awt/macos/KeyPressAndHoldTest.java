/*
 * Copyright 2000-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;

/**
 * @test
 * @summary Regression test for JRE-998: Input freezes after MacOS key-selector on Mojave
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 * @run main KeyPressAndHoldTest
 */

/*
 * Description: Tests that user input continues normally after using Press&Hold feature of maxOS.
 * Robot holds down sample key so accent popup menu may appear and then types sample text.
 * Test passes if the sample text was typed correctly.
 */

public class KeyPressAndHoldTest {

    private static final int SAMPLE_KEY = KeyEvent.VK_E;

    private static final String SAMPLE = "échantillon";
    private static final String SAMPLE_BS = "chantillon";
    private static final String SAMPLE_NO_ACCENT = "echantillon";
    private static final String SAMPLE_MISPRINT = "e0chantillon";

    private static final String PRESS_AND_HOLD_IS_DISABLED = "eeeeeeeeee";

    private static final int PAUSE = 2000;

    private static volatile String result;

    private static Robot robot;

    private static int getMajorMinorMacOsVersion() {
        int version = 0;
        String versionProp = System.getProperty("os.version");
        if (versionProp != null && !versionProp.isEmpty()) {
            String[] versionComponents = versionProp.split("\\.");
            String majorMinor =  versionComponents[0];
            if (versionComponents.length > 1) {
                majorMinor += versionComponents[1];
            }
            try {
                version = Integer.parseInt(majorMinor);
            } catch (NumberFormatException nfexception) {
                // Do nothing
            }
        }
        return version;
    }

    /*
     * Hold down sample key so accents popup menu may appear
     */
    private static void holdDownSampleKey() {
        robot.waitForIdle();
        for (int i = 0; i < 10; i++) {
            robot.keyPress(SAMPLE_KEY);
        }
        robot.keyRelease(SAMPLE_KEY);
    }

    /*
     * Type sample text except the first sample character
     */
    private static void typeSampleBody() {
        robot.delay(PAUSE);
        for (int utfCode : SAMPLE.substring(1).toCharArray()) {
            int keyCode = KeyEvent.getExtendedKeyCodeForChar(utfCode);
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
        }
        robot.delay(PAUSE);
        robot.waitForIdle();
    }

    /*
     * Type sample by selecting accent for the sample key from the popup dialog
     */
    private static void sample() {
        holdDownSampleKey();
        robot.keyPress(KeyEvent.VK_2);
        robot.keyRelease(KeyEvent.VK_2);
        typeSampleBody();
    }

    /*
     * Do not select any accent for the sample key but press backspace to delete the key
     */
    private static void sampleBS() {
        holdDownSampleKey();
        robot.keyPress(KeyEvent.VK_BACK_SPACE);
        robot.keyRelease(KeyEvent.VK_BACK_SPACE);
        typeSampleBody();
    }

    /*
     * Do not select any accent for the sample key from the popup dialog
     */
    private static void sampleNoAccent() {
        holdDownSampleKey();
        robot.keyPress(KeyEvent.VK_ESCAPE);
        robot.keyRelease(KeyEvent.VK_ESCAPE);
        typeSampleBody();
    }

    /*
     * Miss to select any accent for the sample key
     */
    private static void sampleMisprint() {
        holdDownSampleKey();
        robot.keyPress(KeyEvent.VK_0);
        robot.keyRelease(KeyEvent.VK_0);
        typeSampleBody();
    }

    public static void main(String[] args) throws InterruptedException, InvocationTargetException {

        if (GraphicsEnvironment.isHeadless()) {
            throw new RuntimeException("ERROR: Cannot execute the test in headless environment");
        }

        int osVersion  = getMajorMinorMacOsVersion();
        if (osVersion == 0) {
            throw new RuntimeException("ERROR: Cannot determine MacOS version");
        } else if (osVersion < 107) {
            System.out.println("TEST SKIPPED: No Press&Hold feature for Snow Leopard or lower MacOS version");
            return;
        }

        final JFrame frame = new JFrame(KeyPressAndHoldTest.class.getName());
        final CountDownLatch frameGainedFocus = new CountDownLatch(1);
        final JTextArea textArea = new JTextArea();

        final WindowAdapter frameFocusListener = new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                frameGainedFocus.countDown();
            }
        };

        final DocumentListener documentListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                result = textArea.getText();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                result = textArea.getText();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                // No such events for plain text components
            }
        };

        final Runnable frameRunner = () -> {
            textArea.getDocument().addDocumentListener(documentListener);
            frame.getContentPane().add(textArea);
            frame.setSize(400, 200);
            frame.setLocation(100, 100);
            frame.addWindowFocusListener(frameFocusListener);
            frame.setVisible(true);
        };

        final Runnable cleanTextArea = () -> textArea.setText("");

        final Runnable disposeRunner = () -> {
            textArea.getDocument().removeDocumentListener(documentListener);
            frame.removeWindowFocusListener(frameFocusListener);
            frame.dispose();
        };

        try {
            robot = new Robot();
            robot.setAutoDelay(50);

            SwingUtilities.invokeLater(frameRunner);
            frameGainedFocus.await();

            holdDownSampleKey();
            if (PRESS_AND_HOLD_IS_DISABLED.equals(result)) {
                throw new RuntimeException("ERROR: Test requires ApplePressAndHoldEnabled system property set to true");
            }
            SwingUtilities.invokeLater(cleanTextArea);

            int exitValue = 0;

            sample();
            if (!SAMPLE.equals(result)) {
                System.err.println("Bad sample: expected \"" + SAMPLE + "\", but received \"" + result + "\"");
                exitValue = 1;
            }
            SwingUtilities.invokeLater(cleanTextArea);

            sampleBS();
            if (!SAMPLE_BS.equals(result)) {
                System.err.println("Bad sample: expected \"" + SAMPLE_BS + "\", but received \"" + result + "\"");
                exitValue = 1;
            }
            SwingUtilities.invokeLater(cleanTextArea);

            sampleNoAccent();
            if (!SAMPLE_NO_ACCENT.equals(result)) {
                System.err.println("Bad sample: expected \"" + SAMPLE_NO_ACCENT + "\", but received \"" + result + "\"");
                exitValue = 1;
            }
            SwingUtilities.invokeLater(cleanTextArea);

            sampleMisprint();
            if (!SAMPLE_MISPRINT.equals(result)) {
                System.err.println("Bad sample: expected \"" + SAMPLE_MISPRINT + "\", but received \"" + result + "\"");
                exitValue = 1;
            }
            SwingUtilities.invokeLater(cleanTextArea);

            if (exitValue == 0) {
                System.out.println("TEST PASSED");
            } else {
                throw new RuntimeException("TEST FAILED: User input did not continue normally after accent menu popup");
            }

        } catch (AWTException awtException) {
            throw new RuntimeException("ERROR: Cannot create Robot", awtException);
        } finally {
            SwingUtilities.invokeAndWait(disposeRunner);
            /* Waiting for EDT auto-shutdown */
            Thread.sleep(PAUSE);
        }
    }
}
