# WebFX Maven Plugin

The WebFX Maven plugin allows WebFX Export command to be called from within 
Maven build for updating configuration files.

## How it works

The Maven project pom.xml file is updated to add the webfx-maven-plugin
and is then bound to a build phase such as pre-package.

## Building

The plugin can be built and installed locally using Maven command:

```
    mvn clean install
```

## Trying out

The plugin can be run (once installed see above) using the following Maven command:

```
    mvn -DprojectDirectory="/project/path" -DtargetDirectory="/target/path"  dev.webfx:webfx-maven-plugin:export
```

## Configuration

Configuration consists of calling the export goal of the webfx-maven-plugin
during the pre-package phase of the Maven build lifecycle as illustrated in
the example below:

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
                          <phase>pre-package</phase>
                          <goals>
                              <goal>export</goal>
                          </goals>
                      </execution>
                  </executions>
                  <configuration>
                      <projectDirectory>${basedir}</projectDirectory>                
                      <targetDirectory>${project.build.directory}</targetDirectory>
                      <failOnError>true</failOnError>
                 </configuration>
            </plugin>
        </plugins>
    </build>
```

The failOnError XML element can be set true to fail the build if
the return code from the CLI command is not 0, or set to false
to continue building.

## License

The WebFX Maven Plugin is a free, open-source software licensed under the [Apache License 2.0](LICENSE)
