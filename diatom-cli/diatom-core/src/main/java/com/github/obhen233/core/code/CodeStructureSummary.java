package com.github.obhen233.core.code;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码文件的结构化摘要（发送给模型）
 */
public class CodeStructureSummary {
    private String filePath;
    private String packageName;
    private String className;
    private String classType = "class";  // CLASS, INTERFACE, ENUM
    private List<MemberSummary> members = new ArrayList<>();
    private List<ImportInfo> imports = new ArrayList<>();
    private int approximateLine;

    public static class MemberSummary {
        private String name;
        private String type = "METHOD";  // METHOD, FIELD, INNER_CLASS
        private String signature;
        private int approximateLine;
        private String codeHash;

        public MemberSummary() {}

        public MemberSummary(String name, String type, int line) {
            this.name = name;
            this.type = type;
            this.approximateLine = line;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getSignature() {
            return signature;
        }

        public void setSignature(String signature) {
            this.signature = signature;
        }

        public int getApproximateLine() {
            return approximateLine;
        }

        public void setApproximateLine(int approximateLine) {
            this.approximateLine = approximateLine;
        }

        public String getCodeHash() {
            return codeHash;
        }

        public void setCodeHash(String codeHash) {
            this.codeHash = codeHash;
        }
    }

    public static class ImportInfo {
        private String packageName;
        private boolean isStatic;
        private boolean isWildcard;

        public ImportInfo() {}

        public ImportInfo(String packageName) {
            this.packageName = packageName;
        }

        public ImportInfo(String packageName, boolean isStatic, boolean isWildcard) {
            this.packageName = packageName;
            this.isStatic = isStatic;
            this.isWildcard = isWildcard;
        }

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public boolean isStatic() {
            return isStatic;
        }

        public void setStatic(boolean aStatic) {
            isStatic = aStatic;
        }

        public boolean isWildcard() {
            return isWildcard;
        }

        public void setWildcard(boolean wildcard) {
            isWildcard = wildcard;
        }

        @Override
        public String toString() {
            String prefix = isStatic ? "static " : "";
            String suffix = isWildcard ? ".*" : "";
            return "import " + prefix + packageName + suffix + ";";
        }
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getClassType() {
        return classType;
    }

    public void setClassType(String classType) {
        this.classType = classType;
    }

    public List<MemberSummary> getMembers() {
        return members;
    }

    public void setMembers(List<MemberSummary> members) {
        this.members = members;
    }

    public void addMember(MemberSummary member) {
        this.members.add(member);
    }

    public List<ImportInfo> getImports() {
        return imports;
    }

    public void setImports(List<ImportInfo> imports) {
        this.imports = imports;
    }

    public void addImport(ImportInfo importInfo) {
        this.imports.add(importInfo);
    }

    public int getApproximateLine() {
        return approximateLine;
    }

    public void setApproximateLine(int approximateLine) {
        this.approximateLine = approximateLine;
    }

    @Override
    public String toString() {
        return "CodeStructureSummary{" +
                "filePath='" + filePath + '\'' +
                ", packageName='" + packageName + '\'' +
                ", className='" + className + '\'' +
                ", members=" + members.size() +
                ", imports=" + imports.size() +
                '}';
    }
}
