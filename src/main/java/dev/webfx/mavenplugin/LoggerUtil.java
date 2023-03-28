package dev.webfx.mavenplugin;

import dev.webfx.cli.core.Logger;
import org.apache.maven.plugin.logging.Log;

/**
 * @author Bruno Salmon
 */
final class LoggerUtil {

    static void configureWebFXLoggerForMaven(Log mavenLog) {
        Logger.setLogConsumer(msg -> {
            String text = msg.toString();
            if (text.startsWith("WARNING: "))
                mavenLog.warn(text.substring(9));
            else if (text.startsWith("VERBOSE: "))
                mavenLog.debug(text.substring(9));
            else if (text.startsWith("ERROR: "))
                mavenLog.error(text.substring(7));
            else
                mavenLog.info(text);
        });
    }

}
