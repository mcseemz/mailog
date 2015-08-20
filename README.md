# mailog
Rulebased mail manager for processing templated logs on email.<br>Made for people whose production logs/alerts are sent to email.
Squeezes data from matched emails into formatted reports and sends them back (or anywhere).

version 0.4-beta

Works in two modes - archiver and monitor.<br>
<b>Archiver</b> connects to all specified folders on specified mailboxes, parses all messages one-time, rule-matching them.<br>
<b>Monitor</b> looks for specified folders activity using the same rule-matching logic realtime.

Both modes can work simultaneously, mixing results in one report letter.

Report letters are prefixed as "RPT" so they are skipped from further matching.

<h3>Terms</h3>

field - placeholder filled during email parsing. There are several predifined fields, like email date/time, sender etc.<br>
record - fields set after all rules applied to email or email section.<br>
email section - independently processed part of email.<br>
template section - part of template to fill with record' data.<br>
message - source email.<br>
report - resulting email with processed data from messages.<br>
alert - notification email sent when activity conditions are met.


<h3>High-level mechanics</h3>
IMAP does no allow to filter server-side using regexp, so all emails downloaded.

1. Email body split to sections and these sections are used to fill fieldsets along with fields parsed from subject.<br>
2. Each fieldset passed to according template's report.<br>
3. After template record limit reached, report email sent to specified mailbox. Then all participated emails deleted.<br>

<h3>Mailboxes, Rules and Templates description</h3>
for detailed commented description check <a href='json-description.txt'>json-description.txt</a>

<h3>Sections</h3>
Sections have two aspects in <b>mailog</b>:
 1. email parsing<br>
 Source email split to independent parts, then rule body filters applied to each part.
 It covers scenario where you have several similar records in one email so you need several records in output report.
 Another scenario is several distinct parts in email that go to defferent report parts.

 Body rules applies to each section, but in the end at least one mandatory field shoud be filled, so the system knows that this section has something valuable and should pass info to template

 2. template composition<br>
 template can be split by sections, each with it's own formatting. For example, header section with static text and no fields.
 in section you define mandatory fields that should be present in record for it to get to that section.


<h3>Alerts</h3>
Alerts are used to detect increase of activity in corresponding template.
Basically they count records added to template. Upon reaching the predefined limit for predifined period they send letter
with basic statictics and parsed details on last record.

Alerts defined in template. You can define thas much as you want, they act independently.
frequency defined in "5m" style, where quantifier can be 'm' or 'h'.
Also, keyword 'today' supported to count only events since 00:00

for detailed syntax check <a href='json-description.txt'>json-description.txt</a>.

<h3>Autoreload</h3>

mailog has an abiility to update templates and rules on the fly. It watches templates/rules folders for activity and processes new/updated files.
Warning: all object matching goes on name attribute in object, not filename!

When new/updated filed detected:

1. if name and version already exist in runtime, file skipped.
2. if name not exist, this is new object, we should add it to processing list. Mind that if you add rule with link on non-existing template, result is unpredictable.
3. if name exist but version differs, update procedure fills fields not touching runtime parts. This means:
- template sections are updated, but their reports not reset. So you'll get part of report in old style.
- if old/new template sections list differs - it will be adjusted.
- alerts updated according to alert names. Same - updated, new - added, obsolete - removed. If alert count changed then according statistics list changed. This leads to some border cases,
like stats become ready to send alert now. Still, one more event will be required.
- template embedded in rule updates no matter how its name changed. Though, it is possible to change embedded template to external.

no file deletion currently supported. So if you want to stop some template/rule from working - break it and update.
rule cannot change mailbox/folder currently.

<h3>Scenarios</h3>
TBD


<h3>Plans</h3>
<ol>
<li>Semiautomatc learning mode - understanding templates.</li>
<li>Switch System.out to log4j</li>
<li>Complete documentation</li>
</ol>

Any feedback welcome.