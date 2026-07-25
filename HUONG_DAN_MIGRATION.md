# Hướng dẫn Liquibase Migration

## Yêu cầu

- PostgreSQL đang chạy trên port `5433` (qua Docker)
- Đang ở thư mục root project: `c:\Users\phankien\Documents\JAVA`

---

## Các lệnh Migration

### 1. Chạy migration (tạo/cập nhật bảng)

```powershell
.\mvnw.cmd -pl user-service liquibase:update
```

### 2. Xem trạng thái migration

```powershell
.\mvnw.cmd -pl user-service liquibase:status
```

### 3. Rollback (quay lại)

**Rollback 1 bước:**
```powershell
.\mvnw.cmd -pl user-service liquibase:rollback "-Dliquibase.rollbackCount=1"
```

**Rollback 2 bước:**
```powershell
.\mvnw.cmd -pl user-service liquibase:rollback "-Dliquibase.rollbackCount=2"
```

**Rollback N bước:**
```powershell
.\mvnw.cmd -pl user-service liquibase:rollback "-Dliquibase.rollbackCount=N"
```

> ⚠️ **Lưu ý:** Trong PowerShell phải bọc `-Dliquibase.rollbackCount=N` trong dấu `""`

### 4. Xem SQL preview (không thực thi)

```powershell
.\mvnw.cmd -pl user-service liquibase:updateSQL
```

### 5. Xoá toàn bộ bảng trong database

```powershell
.\mvnw.cmd -pl user-service liquibase:dropAll
```

> ⚠️ **CẢNH BÁO:** Lệnh này xoá TẤT CẢ bảng và dữ liệu. Chỉ dùng khi development!

### 6. Xem lịch sử thay đổi

```powershell
.\mvnw.cmd -pl user-service liquibase:history
```

---

## Cấu trúc thư mục Migration

```
user-service/src/main/resources/db/
└── changelog/
    ├── db.changelog-master.yaml          ← File chính (khai báo thứ tự)
    └── changes/
        ├── 001-create-users-table.yaml       ← Tạo bảng users
        ├── 002-create-students-table.yaml    ← Tạo bảng students
        └── 003-create-classrooms-table.yaml  ← Tạo bảng classrooms
```

---

## Cách tạo Migration mới

### Bước 1: Tạo file changelog

Tạo file mới trong `user-service/src/main/resources/db/changelog/changes/`

Ví dụ: `004-add-major-to-students.yaml`

```yaml
databaseChangeLog:
  - changeSet:
      id: 004-add-major-to-students
      author: phankien
      changes:
        - addColumn:
            tableName: students
            columns:
              - column:
                  name: major
                  type: VARCHAR(100)
      rollback:
        - dropColumn:
            tableName: students
            columnName: major
```

### Bước 2: Thêm vào master changelog

Mở file `db/changelog/db.changelog-master.yaml` và thêm dòng:

```yaml
  - include:
      file: db/changelog/changes/004-add-major-to-students.yaml
```

### Bước 3: Chạy migration

```powershell
.\mvnw.cmd -pl user-service liquibase:update
```

---

## Các thao tác phổ biến trong Changelog

### Tạo bảng

```yaml
- createTable:
    tableName: ten_bang
    columns:
      - column:
          name: id
          type: BIGSERIAL
          autoIncrement: true
          constraints:
            primaryKey: true
            nullable: false
      - column:
          name: ten_cot
          type: VARCHAR(255)
          constraints:
            nullable: false
            unique: true
```

### Thêm cột

```yaml
- addColumn:
    tableName: ten_bang
    columns:
      - column:
          name: ten_cot_moi
          type: VARCHAR(100)
          defaultValue: "gia_tri_mac_dinh"
```

### Xoá cột

```yaml
- dropColumn:
    tableName: ten_bang
    columnName: ten_cot
```

### Đổi tên cột

```yaml
- renameColumn:
    tableName: ten_bang
    oldColumnName: ten_cu
    newColumnName: ten_moi
    columnDataType: VARCHAR(100)
```

### Tạo index

```yaml
- createIndex:
    indexName: idx_ten_bang_ten_cot
    tableName: ten_bang
    columns:
      - column:
          name: ten_cot
```

### Thêm khoá ngoại

```yaml
- addForeignKeyConstraint:
    baseTableName: students
    baseColumnNames: classroom_id
    referencedTableName: classrooms
    referencedColumnNames: id
    constraintName: fk_students_classroom
    onDelete: SET NULL
```

---

## Quy tắc đặt tên

| Thành phần | Quy tắc | Ví dụ |
|---|---|---|
| File | `{số thứ tự}-{mô tả}.yaml` | `004-add-major-to-students.yaml` |
| Changeset ID | Giống tên file (không có .yaml) | `004-add-major-to-students` |
| Index | `idx_{tên bảng}_{tên cột}` | `idx_students_email` |
| Foreign Key | `fk_{bảng con}_{bảng cha}` | `fk_students_classroom` |

---

## Lưu ý quan trọng

1. **KHÔNG BAO GIỜ** sửa file migration đã chạy — luôn tạo file mới
2. **Luôn viết `rollback`** cho mỗi changeset để có thể quay lại
3. **Test rollback** trước khi push code: chạy `update` → `rollback` → `update`
4. **Thứ tự trong `db.changelog-master.yaml`** rất quan trọng — không đổi thứ tự
5. App khởi động **KHÔNG** tự chạy migration (đã tắt `liquibase.enabled=false`)
