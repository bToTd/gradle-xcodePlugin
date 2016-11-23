package org.openbakery

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.openbakery.codesign.Codesign
import org.openbakery.output.TestBuildOutputAppender
import org.openbakery.test.TestResultParser
import org.openbakery.testdouble.SimulatorControlStub
import org.openbakery.testdouble.XcodeFake
import org.openbakery.util.PlistHelper
import org.openbakery.xcode.DestinationResolver
import spock.lang.Specification

/**
 * User: rene
 * Date: 25/10/16
 */
class XcodeTestRunTaskSpecification extends Specification {

	Project project
	CommandRunner commandRunner = Mock(CommandRunner);

	XcodeTestRunTask xcodeTestRunTestTask
	File outputDirectory
	File tmpDir


	def setup() {
		tmpDir = new File(System.getProperty("java.io.tmpdir"), "gxp")
		File projectDir = new File(tmpDir, "gradle-projectDir")
		project = ProjectBuilder.builder().withProjectDir(projectDir).build()

		project.apply plugin: org.openbakery.XcodePlugin

		xcodeTestRunTestTask = project.getTasks().getByPath(XcodePlugin.XCODE_TEST_RUN_TASK_NAME)
		xcodeTestRunTestTask.commandRunner = commandRunner
		xcodeTestRunTestTask.xcode = new XcodeFake()
		xcodeTestRunTestTask.destinationResolver = new DestinationResolver(new SimulatorControlStub("simctl-list-xcode8.txt"))


		outputDirectory = new File(project.buildDir, "test")
		if (!outputDirectory.exists()) {
			outputDirectory.mkdirs()
		}


	}

	def cleanup() {
		FileUtils.deleteDirectory(tmpDir)
	}


	def "instance is of type XcodeBuildForTestTask"() {
		expect:
		xcodeTestRunTestTask instanceof  XcodeTestRunTask
	}


	def "depends on nothing "() {
		when:
		def dependsOn = xcodeTestRunTestTask.getDependsOn()
		then:
		dependsOn.size() == 2
		dependsOn.contains(XcodePlugin.SIMULATORS_KILL_TASK_NAME)
	}


	def "set destinations"() {
		when:
		xcodeTestRunTestTask.destination = [
						"iPhone 6"
		]

		then:
		xcodeTestRunTestTask.parameters.configuredDestinations.size() == 1
		xcodeTestRunTestTask.parameters.configuredDestinations[0].name == "iPhone 6"
	}


	def "set destination global"() {
		when:
		project.xcodebuild.destination = [
						"iPhone 6"
		]

		xcodeTestRunTestTask.testRun()

		then:
		xcodeTestRunTestTask.parameters.configuredDestinations.size() == 1
		xcodeTestRunTestTask.parameters.configuredDestinations[0].name == "iPhone 6"

	}


	def "has bundle directory"() {
		when:
		xcodeTestRunTestTask.bundleDirectory  = "test"

		then:
		xcodeTestRunTestTask.bundleDirectory instanceof File
	}


	def createTestBundle(String directoryName) {
		File bundleDirectory = new File(project.getProjectDir(), directoryName)
		File testBundle = new File(bundleDirectory, "Example.testbundle")
		testBundle.mkdirs()
		File xctestrun = new File("src/test/Resource/Example_iphonesimulator.xctestrun")
		FileUtils.copyFile(xctestrun, new File(testBundle, "Example_iphonesimulator.xctestrun"))
	}


	def "set configure xctestrun"() {
		given:
		createTestBundle("test")

		when:
		xcodeTestRunTestTask.bundleDirectory  = "test"
		xcodeTestRunTestTask.testRun()

		then:
		xcodeTestRunTestTask.parameters.xctestrun instanceof List
		xcodeTestRunTestTask.parameters.xctestrun.size() == 1
		xcodeTestRunTestTask.parameters.xctestrun[0].path.endsWith("Example.testbundle/Example_iphonesimulator.xctestrun")
	}


	def "run xcodebuild executeTestWithoutBuilding"() {
		given:
		def commandList
		createTestBundle("test")

		when:
		xcodeTestRunTestTask.testRun()

		then:
		1 * commandRunner.run(_, _, _, _) >> { arguments -> commandList = arguments[1] }
		commandList.contains("test-without-building")
		commandList.contains("-xctestrun")

	}

	def "has output appender"() {
		def outputAppender

		when:
		xcodeTestRunTestTask.testRun()

		then:
		1 * commandRunner.run(_, _, _, _) >> { arguments -> outputAppender = arguments[3] }
		outputAppender instanceof TestBuildOutputAppender
	}

	def "delete derivedData/Logs/Test before test is executed"() {
		project.xcodebuild.target = "Test"

		def testDirectory = new File(project.xcodebuild.derivedDataPath, "Logs/Test")
		FileUtils.writeStringToFile(new File(testDirectory, "foobar"), "dummy");

		when:
		xcodeTestRunTestTask.testRun()

		then:
		!testDirectory.exists()
	}


	def fakeTestRun() {
		xcodeTestRunTestTask.destinationResolver.simulatorControl = new SimulatorControlStub("simctl-list-xcode7.txt");

		project.xcodebuild.destination {
			name = "iPad 2"
		}
		project.xcodebuild.destination {
			name = "iPhone 4s"
		}


		xcodeTestRunTestTask.setOutputDirectory(outputDirectory);
		File xcodebuildOutput = new File(project.buildDir, 'test/xcodebuild-output.txt')
		FileUtils.writeStringToFile(xcodebuildOutput, "dummy")
	}

	def "parse test-result.xml gets stored"() {
		given:
		project.xcodebuild.target = "Test"

		when:
		xcodeTestRunTestTask.testRun()

		def testResult = new File(outputDirectory, "test-results.xml")
		then:
		testResult.exists()
	}


	def "has TestResultParser"() {
		given:
		project.xcodebuild.target = "Test"

		when:
		fakeTestRun()
		xcodeTestRunTestTask.testRun()

		then:
		xcodeTestRunTestTask.testResultParser instanceof TestResultParser
		xcodeTestRunTestTask.testResultParser.testSummariesDirectory == new File(project.buildDir, "derivedData/Logs/Test")
		xcodeTestRunTestTask.testResultParser.destinations.size() == 2

	}

	def "output file was set"() {
		def givenOutputFile
		project.xcodebuild.target = "Test"

		when:
		xcodeTestRunTestTask.testRun()

		then:
		1 * commandRunner.setOutputFile(_) >> { arguments -> givenOutputFile = arguments[0] }
		givenOutputFile.absolutePath.endsWith("xcodebuild-output.txt")
		givenOutputFile == new File(project.getBuildDir(), "test/xcodebuild-output.txt")

	}



	def "simulator build has no keychain dependency"() {
		when:
		xcodeTestRunTestTask = project.getTasks().getByPath(XcodePlugin.XCODE_TEST_RUN_TASK_NAME)
		xcodeTestRunTestTask.destination {
			platform = "iOS Simulator"
			name = "iPad Air"
		}
		project.evaluate()

		then:
		!xcodeTestRunTestTask.getTaskDependencies().getDependencies().contains(project.getTasks().getByName(XcodePlugin.KEYCHAIN_CREATE_TASK_NAME))
		!xcodeTestRunTestTask.getTaskDependencies().getDependencies().contains(project.getTasks().getByName(XcodePlugin.PROVISIONING_INSTALL_TASK_NAME))
	}

	def "when keychain dependency then also has finalized keychain remove"() {
		when:
		xcodeTestRunTestTask = project.getTasks().getByPath(XcodePlugin.XCODE_TEST_RUN_TASK_NAME)
		xcodeTestRunTestTask.destination {
			platform = "iOS"
			name = "Dummy"
		}
		project.evaluate()

		then:
		xcodeTestRunTestTask.finalizedBy.values.contains(XcodePlugin.KEYCHAIN_REMOVE_SEARCH_LIST_TASK_NAME)
	}


	def "has keychain dependency if device run"() {
		when:
		xcodeTestRunTestTask = project.getTasks().getByPath(XcodePlugin.XCODE_TEST_RUN_TASK_NAME)
		xcodeTestRunTestTask.destination {
			platform = "iOS"
			name = "Dummy"
		}
		project.evaluate()

		then:
		xcodeTestRunTestTask.getTaskDependencies().getDependencies().contains(project.getTasks().getByName(XcodePlugin.KEYCHAIN_CREATE_TASK_NAME))
		xcodeTestRunTestTask.getTaskDependencies().getDependencies().contains(project.getTasks().getByName(XcodePlugin.PROVISIONING_INSTALL_TASK_NAME))
	}


	def "has codesign"() {
		when:
		xcodeTestRunTestTask = project.getTasks().getByPath(XcodePlugin.XCODE_TEST_RUN_TASK_NAME)
		xcodeTestRunTestTask.destination {
			platform = "iOS"
			name = "Dummy"
		}
		project.evaluate()

		then:
		xcodeTestRunTestTask.getCodesign() != null
	}

	def "simulator has no codesign"() {
		when:
		xcodeTestRunTestTask = project.getTasks().getByPath(XcodePlugin.XCODE_TEST_RUN_TASK_NAME)
		xcodeTestRunTestTask.destination {
			platform = "iOS Simulator"
			name = "iPad Air"
		}
		project.evaluate()

		then:
		xcodeTestRunTestTask.getCodesign() == null
	}


	def createTestBundleForDeviceBuild() {
		File bundleDirectory = new File(project.getProjectDir(), "for-testing")
		File testBundle = new File(bundleDirectory, "DemoApp-iOS.testbundle")
		File appBundle = new File(testBundle, "Debug-iphoneos/DemoApp.app")
		appBundle.mkdirs()

		def frameworks = ["IDEBundleInjection.framework", "OBTableViewController.framework", "XCTest.framework"]
		for (String framework : frameworks) {
			File frameworkBundle = new File(appBundle, "Frameworks/" + framework)
			frameworkBundle.mkdirs()
		}
		File xctestrun = new File("../libtest/src/main/Resource/DemoApp_iphoneos10.1-arm64.xctestrun")
		FileUtils.copyFile(xctestrun, new File(testBundle, "DemoApp_iphoneos10.1-arm64.xctestrun"))

		File infoPlist = new File(appBundle, "Info.plist")
		PlistHelper helper = new PlistHelper(new CommandRunner())
		helper.create(infoPlist)
		helper.addValueForPlist(infoPlist, "CFBundleIdentifier", "org.openbakery.test.Example")

		return bundleDirectory
	}


	void mockEntitlementsFromPlist(File provisioningProfile) {
		def commandList = ['security', 'cms', '-D', '-i', provisioningProfile.absolutePath]
		String result = new File('../libtest/src/main/Resource/entitlements.plist').text
		commandRunner.runWithResult(commandList) >> result
		String basename = FilenameUtils.getBaseName(provisioningProfile.path)
		File plist = new File(tmpDir, "/provision_" + basename + ".plist")
		commandList = ['/usr/libexec/PlistBuddy', '-x', plist.absolutePath, '-c', 'Print Entitlements']
		commandRunner.runWithResult(commandList) >> result
	}


	def "bundle is codesigned"() {
		given:
		def commandList
		def bundleDirectory = createTestBundleForDeviceBuild()
		def mobileprovision = new File("../libtest/src/main/Resource/test.mobileprovision")
		mockEntitlementsFromPlist(mobileprovision)
		project.xcodebuild.signing.mobileProvisionFile = mobileprovision

		xcodeTestRunTestTask = project.getTasks().getByPath(XcodePlugin.XCODE_TEST_RUN_TASK_NAME)
		xcodeTestRunTestTask.destination {
			platform = "iOS"
			name = "Dummy"
		}
		xcodeTestRunTestTask
		project.evaluate()

		xcodeTestRunTestTask.setBundleDirectory(bundleDirectory )

		when:
		xcodeTestRunTestTask.testRun()

		then:

		5 * commandRunner.run(_, _) >> { arguments -> commandList = arguments[0] }
		commandList.contains("/usr/bin/codesign")
	}



	def "sign test bundle path"() {
		given:
		def bundleDirectory = createTestBundleForDeviceBuild()
		def mobileprovision = new File("../libtest/src/main/Resource/test.mobileprovision")
		mockEntitlementsFromPlist(mobileprovision)
		project.xcodebuild.signing.mobileProvisionFile = mobileprovision

		xcodeTestRunTestTask = project.getTasks().getByPath(XcodePlugin.XCODE_TEST_RUN_TASK_NAME)
		xcodeTestRunTestTask.destination {
			platform = "iOS"
			name = "Dummy"
		}
		xcodeTestRunTestTask
		project.evaluate()
		xcodeTestRunTestTask.setBundleDirectory(bundleDirectory )

		def codesign = Mock(Codesign)
		xcodeTestRunTestTask.codesign = codesign


		when:
		xcodeTestRunTestTask.testRun()

		then:
		1 * codesign.sign(new File(bundleDirectory, "DemoApp-iOS.testbundle/Debug-iphoneos/DemoApp.app"))
		1 * codesign.sign(new File(bundleDirectory, "DemoApp-iOS.testbundle/Debug-iphoneos/DemoApp.app/PlugIns/Tests.xctest"))

	}
}