@GrabConfig(systemClassLoader = true)
@Grapes([
        @Grab(group = 'org.glassfish.jersey.core', module = 'jersey-client', version = '2.25.1'),
        @Grab(group = 'org.glassfish.jersey.media', module = 'jersey-media-json-jackson', version = '2.25.1'),
        @Grab(group = 'mysql', module = 'mysql-connector-java', version = '6.0.6'),
        @Grab(group = 'org.apache.commons', module = 'commons-lang3', version = '3.6'),
        @Grab(group = 'org.apache.poi', module = 'poi', version = '3.16'),
])

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import groovy.transform.Immutable
import groovy.transform.ToString
import org.apache.commons.lang3.StringUtils
import groovy.sql.Sql
import org.apache.poi.ss.usermodel.Hyperlink
import org.apache.poi.common.usermodel.HyperlinkType
import org.apache.poi.hssf.usermodel.HSSFCell
import org.apache.poi.hssf.usermodel.HSSFCellStyle
import org.apache.poi.hssf.usermodel.HSSFRow
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hssf.usermodel.HSSFSheet
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.Font
import org.apache.poi.ss.usermodel.IndexedColors

import javax.ws.rs.client.ClientBuilder
import javax.ws.rs.client.WebTarget

import static java.util.Objects.isNull
import static java.util.Objects.nonNull

@ToString
@Immutable
class SonarPaging {
    int pageIndex
    int pageSize
    int total
}

@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
@Immutable
class SonarIssue {
    String key
    String component
    String project
    String rule
    int line
}

@JsonIgnoreProperties(ignoreUnknown = true)
@ToString
@Immutable
class SonarIssues {
    SonarPaging paging
    List<SonarIssue> issues
}

@ToString
@Immutable
class TestHarnessIssue {
    int issueId
    int issueLine
    int issueColumn
    String issueFile
    String projectName
    String normalizedProjectName
    String ruleName
}

@ToString
@Immutable
class TestHarnessProject {
    String projectName
    String normalizedProjectName
}

@ToString
@Immutable
class JobDetails {
    Set<TestHarnessProject> jobProjects
    Set<String> jobRules
    List<TestHarnessIssue> jobIssues
}

@Immutable
class IssueKey {
    String projectName
    String ruleName
    String fileName
    int line
}

@Immutable
class ReportItem {
    int line
    int column
    String file
    String cause
}

@Immutable
class ProjectAnalysisResult {
    String projectName
    List<TestHarnessIssue> withSonarMatch
    List<TestHarnessIssue> withoutSonarMatchTest
    List<TestHarnessIssue> withoutSonarMatchNotTest
    List<SonarIssue> withoutTestHarnessMatch
}

static String normalizedRuleName(String ruleName) {
    switch (ruleName) {
        case { String it -> StringUtils.startsWithAny(it, "findbugs", "squid") }:
            return ruleName
        case ~/^S[0-9]*$/:
            return "squid:${ruleName}"
        case { String it -> StringUtils.isAllUpperCase(StringUtils.replace(it, "_", "")) }:
            return "findbugs:${ruleName}"
        default:
            return "squid:${ruleName}"
    }
}

static String extractSonarFile(String fileName) {
    return StringUtils.substringAfterLast(fileName, ":")
}

static String extractOnlyFilename(String fullPath) {
    return StringUtils.substringAfterLast(fullPath, "/")
}

static Sql createSql() {
    def dbUrl = 'jdbc:mysql://devfactory-aurora-1.cluster-cd1ianm7fpxp.us-east-1.rds.amazonaws.com';
    def dbPort = 3306
    def dbSchema = 'javabrp_harness_tst'
    def dbUser = 'javabrp_harness'
    def dbPassword = '9&4i0@&k98Wp'
    def dbDriver = 'com.mysql.cj.jdbc.Driver'
    return Sql.newInstance(dbUrl + ':' + dbPort + '/' + dbSchema, dbUser, dbPassword, dbDriver)
}

static JobDetails loadTestHarnessIssues(Integer jobId) {
    def testHarnessToSonarProject = [
            "brp-java-test-apache-maven": "org.apache.maven:maven",
            "brp-java-test-antlr4": "org.antlr:antlr4-master",
            "brp-java-test-struts": "org.apache.struts:struts2-parent",
            "brp-java-test-jenkins": "org.jenkins-ci.main:pom",
            "brp-java-test-jetty": "org.eclipse.jetty:jetty-project",
            "brp-java-test-hibernate-orm": "hibernate-orm",
            "brp-java-test-jgit": "org.eclipse.jgit:org.eclipse.jgit-parent",
            "brp-java-test-bouncy-castle": "bc-java",
            "brp-java-test-drools": "org.drools:drools",
            "brp-java-test-apache-wicket": "org.apache.wicket:wicket-parent",
            "brp-java-test-hive": "org.apache.hive:hive",
            "brp-java-test-spring-framework": "org.springframework:spring"
    ]
    Sql sql = createSql()
    List<TestHarnessIssue> jobIssues = sql.rows(
            "SELECT i.id AS issueId, i.Line AS issueLine, i.Col AS issueColumn, i.File AS issueFile, p.Name AS projectName, r.Name AS ruleName FROM issues i " +
            "JOIN projects p ON i.ProjectId = p.Id " +
            "JOIN neo4jrules r ON i.RuleId = r.Id " +
            "WHERE i.AnalysisJobId = ?", [jobId]).collect { row ->
                            new TestHarnessIssue([issueId: row.issueId, issueLine: row.issueLine,
                                                  issueColumn: row.issueColumn, issueFile: row.issueFile,
                                                  ruleName: normalizedRuleName(row.ruleName),
                                                  projectName: row.projectName,
                                                  normalizedProjectName: testHarnessToSonarProject[row.projectName]])}
    Set<TestHarnessProject> jobProjects = sql.rows("SELECT p.Name AS projectName FROM projecttoanalysisjob a " +
            "JOIN projects p ON a.Project_Id = p.Id WHERE a.AnalysisJob_Id = ?", [jobId]).collect { row ->
        new TestHarnessProject([projectName: row.projectName,
                                normalizedProjectName: testHarnessToSonarProject[row.projectName]])
    }
    Set<String> jobRules = sql.rows("SELECT r.Name AS ruleName FROM neo4jruletoanalysisjob a " +
            "JOIN neo4jrules r ON a.Neo4JRule_id = r.Id WHERE a.AnalysisJob_Id = ?", [jobId]).collect {
        normalizedRuleName(it.ruleName)
    }
    return new JobDetails(jobProjects, jobRules, jobIssues)
}

static Set<String> findUnknownProjects(JobDetails jobDetails) {
    return jobDetails.jobProjects.findAll { isNull(it.normalizedProjectName) }.collect { it.projectName }
}

static SonarIssues execute(WebTarget target) {
    target.request().get(SonarIssues)
}

static List<SonarIssue> querySonar(JobDetails jobDetails) {
    def page = 1
    def pageSize = 500
    def baseTarget = ClientBuilder.newClient().target("http://brp-sonar.ecs.devfactory.com")
            .path("/api/issues/search")
            .queryParam("ps", pageSize).queryParam("asc", true)
            .queryParam("rules", jobDetails.jobRules.join(","))
            .queryParam("componentKeys", jobDetails.jobProjects.collect { it.normalizedProjectName }.toSet().join(","))
    def sonarIssuesList = new ArrayList<SonarIssue>()
    def sonarIssues = execute(baseTarget.queryParam("p", page))
    sonarIssuesList.addAll(sonarIssues.issues)
    while (page * pageSize < sonarIssues.paging.total) {
        sonarIssues = execute(baseTarget.queryParam("p", ++page))
        sonarIssuesList.addAll(sonarIssues.issues)
    }
    return sonarIssuesList
}

static IssueKey createKeyForSonar(SonarIssue issue) {
    return new IssueKey([projectName: issue.project,
                         ruleName: issue.rule,
                         fileName: extractOnlyFilename(issue.component),
                         line: issue.line])
}

static IssueKey createKeyForTestHarness(TestHarnessIssue issue) {
    return new IssueKey([projectName: issue.normalizedProjectName,
                         ruleName: issue.ruleName,
                         fileName: extractOnlyFilename(issue.issueFile),
                         line: issue.issueLine])
}

static SonarIssue exactMatch(TestHarnessIssue thIssue, List<SonarIssue> keyMatch) {
    keyMatch.find { sonarIssue -> StringUtils.endsWith(thIssue.issueFile, extractSonarFile(sonarIssue.component)) }
}

static List<ProjectAnalysisResult> analyzeTestHarnessJob(int jobId) {
    println("Query Test Harness job results.")
    JobDetails jobDetails = loadTestHarnessIssues(jobId)
    Set<String> unknownProjects = findUnknownProjects(jobDetails)
    if (!unknownProjects.isEmpty()) {
        println("WARNING: Couldn't find Sonar project(s) for following Test Harness project(s): " + unknownProjects)
    }
    List<TestHarnessIssue> thIssues = jobDetails.jobIssues
    List<TestHarnessIssue> thIssuesKnowProjects = thIssues.findAll { nonNull(it.normalizedProjectName) }
    println("Query Sonar issues.")
    List<SonarIssue> sonarIssues = querySonar(jobDetails)

    Map<IssueKey, List<SonarIssue>> groupedSonar = sonarIssues.groupBy { createKeyForSonar(it) }
    Map<IssueKey, List<TestHarnessIssue>> groupedTestHarness = thIssuesKnowProjects.groupBy { createKeyForTestHarness(it) }

    Set<TestHarnessIssue> thIssuesWithMatchingSonar = new HashSet<>()
    Set<TestHarnessIssue> thIssuesWithoutMatchingSonar = new HashSet<>()
    Set<SonarIssue> sonarWithMatchingTestHarness = new HashSet<>()

    groupedTestHarness.each { key, thIssueList ->
        thIssueList.each { thIssue ->
            SonarIssue sonarIssue = exactMatch(thIssue, groupedSonar.get(key, Collections.emptyList()))
            if (nonNull(sonarIssue)) {
                sonarWithMatchingTestHarness.add(sonarIssue)
                thIssuesWithMatchingSonar.add(thIssue)
            } else {
                thIssuesWithoutMatchingSonar.add(thIssue)
            }
        }
    }
    Set<SonarIssue> sonarWithoutMatchingTestHarness = sonarIssues.findAll { !sonarWithMatchingTestHarness.contains(it) }
    Set<TestHarnessIssue> testThIssues = thIssuesWithoutMatchingSonar.findAll { StringUtils.contains(it.issueFile, "src/test/") }
    Set<TestHarnessIssue> notTestThIssues = thIssuesWithoutMatchingSonar.findAll { !testThIssues.contains(it) }

    Map<String, List<TestHarnessIssue>> withSonarMatchByProject = thIssuesWithMatchingSonar.groupBy { it.normalizedProjectName }
    Map<String, List<TestHarnessIssue>> withoutSonarMatchTestByProject = testThIssues.groupBy { it.normalizedProjectName }
    Map<String, List<TestHarnessIssue>> withoutSonarMatchNotTestByProject = notTestThIssues.groupBy { it.normalizedProjectName }
    Map<String, List<SonarIssue>> withoutTestHarnessMatchByProject = sonarWithoutMatchingTestHarness.groupBy { it.project }

    return jobDetails.jobProjects.collect { jobProject ->
        new ProjectAnalysisResult([projectName: jobProject.projectName,
                                   withSonarMatch: withSonarMatchByProject.get(jobProject.normalizedProjectName, Collections.emptyList()),
                                   withoutSonarMatchTest: withoutSonarMatchTestByProject.get(jobProject.normalizedProjectName, Collections.emptyList()),
                                   withoutSonarMatchNotTest: withoutSonarMatchNotTestByProject.get(jobProject.normalizedProjectName, Collections.emptyList()),
                                   withoutTestHarnessMatch: withoutTestHarnessMatchByProject.get(jobProject.normalizedProjectName, Collections.emptyList()),
        ])
    }
}


static List<ReportItem> convert(List<TestHarnessIssue> thIssues, String withCause) {
    thIssues.collect { thIssue -> new ReportItem([file: thIssue.issueFile,
            line: thIssue.issueLine,
            column: thIssue.issueColumn,
            cause: withCause])
    }
}

static HSSFCellStyle createDataStyle(HSSFWorkbook workbook) {
    def cellStyle = workbook.createCellStyle()
    cellStyle.with {
        borderBottom = borderLeft = borderRight = borderTop = BorderStyle.THIN
    }
    return cellStyle
}

static HSSFCell createCell(HSSFRow row, int index, HSSFCellStyle cellStyle) {
    def cell = row.createCell(index)
    cell.setCellStyle(cellStyle)
    return cell
}

static void createHeader(HSSFSheet sheet, List<String> headers) {
    def workbook = sheet.workbook
    def headerStyle = workbook.createCellStyle()
    def headerFont = workbook.createFont()
    headerFont.bold = true
    headerStyle.with {
        fillBackgroundColor = IndexedColors.GREY_50_PERCENT.getIndex()
        font = headerFont
        borderBottom = borderLeft = borderRight = borderTop = BorderStyle.THICK
    }
    sheet.createRow(0).with { row ->
        headers.eachWithIndex{ String entry, int i ->
            createCell(row, i, headerStyle).setCellValue(entry)
        }
    }
}

static void fillCodeGraphOnlySheet(HSSFSheet sheet, List<ReportItem> reportItems) {
    createHeader(sheet, ["File", "Line", "Column", "Cause"])
    def dataCellSyle = createDataStyle(sheet.workbook)
    List<ReportItem> toSort = new ArrayList<>(reportItems)
    toSort.sort new OrderBy<ReportItem>([{it.file}, {it.line}])
    toSort.eachWithIndex{ ReportItem entry, int i ->
        def row = sheet.createRow(i + 1)
        createCell(row, 0, dataCellSyle).setCellValue(entry.file)
        createCell(row, 1, dataCellSyle).setCellValue(entry.line)
        createCell(row, 2, dataCellSyle).setCellValue(entry.column)
        createCell(row, 3, dataCellSyle).setCellValue(entry.cause)
    }
    (0..3).each { sheet.autoSizeColumn(it) }
}

static void fillSonarOnlySheet(HSSFSheet sheet, List<SonarIssue> reportItems) {
    createHeader(sheet, ["Component", "File"])
    def workbook = sheet.workbook
    def dataCellSyle = createDataStyle(sheet.workbook)
    def hyperLinkStyle = createDataStyle(sheet.workbook)
    def hyperLinkFont = workbook.createFont()
    hyperLinkFont.underline = Font.U_SINGLE
    hyperLinkFont.color = IndexedColors.BLACK.getIndex()
    hyperLinkStyle.font = hyperLinkFont
    List<SonarIssue> toSort = new ArrayList<>(reportItems)
    toSort.sort new OrderBy<SonarIssue>([{it.component}, {it.line}])
    toSort.eachWithIndex{ SonarIssue entry, int i ->
        def row = sheet.createRow(i + 1)
        Hyperlink componentLink = workbook.creationHelper.createHyperlink(HyperlinkType.URL)
        componentLink.setAddress("http://brp-sonar.ecs.devfactory.com/issues/search#issues=" + entry.key)
        def componentCell = createCell(row, 0, hyperLinkStyle)
        componentCell.setCellValue(entry.component)
        componentCell.setHyperlink(componentLink)
        createCell(row, 1, dataCellSyle).setCellValue(entry.line)
    }
    (0..1).each { sheet.autoSizeColumn(it) }
}

static Hyperlink createSheetHyperLink(HSSFSheet sheet, String sheetName) {
    def hyperLink = sheet.workbook.creationHelper.createHyperlink(HyperlinkType.DOCUMENT)
    hyperLink.address = "'" + sheetName + "'!A1"
    return hyperLink
}

static HSSFCell withSheetLink(HSSFCell cell, String sheetName) {
    if (StringUtils.isNotBlank(sheetName)) {
        cell.setHyperlink(createSheetHyperLink(cell.sheet, sheetName))
    }
    return cell
}

static void fillSummaryRow(HSSFSheet sheet, ProjectAnalysisResult result, int index, String cgSheet, String sonarSheet) {
    def cellStyle = createDataStyle(sheet.workbook)
    def row = sheet.createRow(index)
    createCell(row, 0, cellStyle).cellValue = result.projectName
    createCell(row, 1, cellStyle).cellValue = result.withSonarMatch.size()
    withSheetLink(createCell(row, 2, cellStyle), cgSheet).cellValue = result.withoutSonarMatchTest.size()
    withSheetLink(createCell(row, 3, cellStyle), cgSheet).cellValue = result.withoutSonarMatchNotTest.size()
    withSheetLink(createCell(row, 4, cellStyle), sonarSheet).cellValue = result.withoutTestHarnessMatch.size()
    (0..4).each { sheet.autoSizeColumn(it) }
}

static HSSFWorkbook generateXLSReport(List<ProjectAnalysisResult> analysisResults) {
    def workbook = new HSSFWorkbook()
    def summarySheet = workbook.createSheet("Summary")
    createHeader(summarySheet, ["Project", "Match", "Not in Sonar [tests]", "Not in Sonar", "Not in TH"])
    analysisResults.eachWithIndex { result, i ->
        List<ReportItem> reportItems = new ArrayList<>()
        reportItems.addAll(convert(result.withoutSonarMatchTest, "Test code."))
        reportItems.addAll(convert(result.withoutSonarMatchNotTest, ""))
        def shortName = StringUtils.removeStart(result.projectName, "brp-java-test-")
        def codeGraphSheet = ""
        def sonarSheet = ""
        if (!reportItems.isEmpty()) {
            codeGraphSheet = "${shortName}_cg"
            fillCodeGraphOnlySheet(workbook.createSheet(codeGraphSheet), reportItems)
        }
        if (!result.withoutTestHarnessMatch.isEmpty()) {
            sonarSheet = "${shortName}_sonar"
            fillSonarOnlySheet(workbook.createSheet(sonarSheet), result.withoutTestHarnessMatch)
        }
        fillSummaryRow(summarySheet, result, i + 1, codeGraphSheet, sonarSheet)
    }
    return workbook
}

static void printSummaryTable(List<ProjectAnalysisResult> resultList) {
    def seperator = StringUtils.repeat("-", 106)
    println(seperator)
    printf("|%-40s|%15s|%15s|%15s|%15s|%n", "Project", "Match", "No Sonar(test)", "No Sonar", "No TH")
    println(seperator)
    resultList.each {
        printf("|%-40s|%15d|%15d|%15d|%15d|%n", it.projectName, it.withSonarMatch.size(), it.withoutSonarMatchTest.size(),
                it.withoutSonarMatchNotTest.size(), it.withoutTestHarnessMatch.size())
        println(seperator)
    }
}

static void markTestHarnessAsPositive(List<Integer> issuesId) {
    if (!issuesId.isEmpty()) {
        createSql().executeUpdate("UPDATE issues i SET i.IsPositive = 1 WHERE i.id IN (" + issuesId.join(",") + ")")
    }
}

static void performAnalyze(OptionAccessor options) {
    def jobId = options.j as Integer
    println("Analyze Test Harness Job ID: ${jobId}")
    List<ProjectAnalysisResult> resultList = analyzeTestHarnessJob(jobId)
    printSummaryTable(resultList)
    def xlsFilename = "report_${jobId}.xls"
    println("Generating '${xlsFilename}'...")
    new FileOutputStream(xlsFilename).withCloseable {
        generateXLSReport(resultList).write(it)
    }
    if (options.s) {
        println("Marking matching as positive.")
        markTestHarnessAsPositive(resultList.collect { it.withSonarMatch }.flatten().collect { it.issueId })
    }
    if (options.t) {
        println("Marking test as positive.")
        markTestHarnessAsPositive(resultList.collect { it.withoutSonarMatchTest }.flatten().collect { it.issueId })
    }
}

def cli = new CliBuilder(usage: "thSonar [options]", header: "Options:")
cli.j(longOpt: 'job-id', argName: 'jobId', required: true, args: 1, "Test Harness Job ID")
cli.s(longOpt: 'update-db', "Update Test Harness database. Mark marching as positive.")
cli.t(longOpt: 'update-db-test', "Update Test Harness database. Mark test as positive.")

def options = cli.parse(this.args)
if (options && options.j && options.j.isInteger()) {
    performAnalyze(options)
}