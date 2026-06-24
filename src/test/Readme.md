# Tests

End-to-end automated tests are implemented in the JUnit 5 class
fr.jpca.archi.k8simporter.K8sArchiGenerationE2ETest.java and executed using Maven (maven-surefire-plugin and JUnit dependencies).
The end-to-end test consists of:

1. Loading the model provided as a parameter.
2. Running the generation with the namespace and targeting a folder name, for example JAVA_BOOKINFO_<YYYYMMDD>.
3. Retrieving the control folder (BOOKINFO_CONTROL).
4. Iterating through and comparing each element (Pods, Services, etc.) as well as the elements in the Technology & Physical folder.
5. Comparing their properties while ignoring keys starting with generation_*.
6. Comparing incoming and outgoing relationships (source/target).
7. Producing a report in table format displayed in the console and failing if differences are detected.

To run the tests from a terminal:

``bash
mvn test -Dtest=K8sArchiGenerationE2ETest \
         -DarchiModelFile=src/test/java/bookinfo-test-model.archimate \
         -Dk8sNamespace=bookinfo \
         -DcontrolFolder=BOOKINFO_CONTROL \
         -DtargetFolder=BOOKINFO_TEST
``