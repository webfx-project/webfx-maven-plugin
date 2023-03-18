# WebFX Maven Plugin

The WebFX Maven plugin allows WebFX Command line interface (CLI) 
to be called from within Maven build for running tasks such as 
updating dependency files.

## How it works

The Maven project pom.xml file is updated to add the webfx-maven-plugin
and is then bound to a build phase such as: validate, initialize or
post processing stages such as prepare-package.

## Configuration

Configuration consists of calling the cli goal of the webfx-maven-plugin
in the example below during the initilize phase of the Maven build 
lifecycle.

The 'args' XML element below is where the 'webfx' command line arguments
are placed. For example is the command line was: webfx --help 
then the arguments would be as shown below.

```
  <build>
      <pluginManagement>
          <plugins>
              <plugin>
                  <groupId>dev.webfx</groupId>
                  <artifactId>webfx-maven-plugin</artifactId>
                  <version>0.1.0-SNAPSHOT</version>
              </plugin>
          </plugins>
          </pluginManagement>  
          <plugins>
              <plugin>
                  <groupId>dev.webfx</groupId>
                  <artifactId>webfx-maven-plugin</artifactId>
                  <executions>
                      <execution>
                          <phase>initialize</phase>
                          <goals>
                              <goal>cli</goal>
                          </goals>
                      </execution>
                  </executions>
                  <configuration>
                      <args>                
                          <arg>--help</arg>
                      </args>
                      <failOnError>true</failOnError>
                 </configuration>
            </plugin>
        </plugins>
    </build>
```

For each argument supplied a new 'arg' tag should be added to the
'args' element section.

The failOnError XML element can be set true to fail the build if
the return code from the CLI command is not 0, or set to false
to continue building.

## License

The WebFX Maven Plugin is a free, open-source software licensed under the [Apache License 2.0](LICENSE)
