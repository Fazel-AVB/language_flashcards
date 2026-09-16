' Start the app without a lingering console.
' Uses "cmd /k" so the window stays if something fails; close it to stop the app.
Dim strDir
strDir = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
CreateObject("WScript.Shell").Run _
    "cmd /k cd /d """ & strDir & """ && python -m streamlit run app.py", 1, False
