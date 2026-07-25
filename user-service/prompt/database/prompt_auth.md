# Tài Liệu Cấu Trúc Database - Module Authentication 

## User : Lưu trữ thông tin tài khoản người dùng

- **id**
  - Định danh duy nhất (Primary Key)
  - UUID (Auto-generate)
- **email**
  - Địa chỉ email dùng để đăng nhập (Unique)
  - String (VarChar 255)
- **email_verified_at**
  - Thời điểm xác thực email (Dùng cho tính năng Verify Email)
  - DateTime (Optional)
- **name**
  - Tên hiển thị người dùng (Unique)
  - String (VarChar 100)
- **phone_number**
  - Số điện thoại liên hệ
  - String (VarChar 20)
- **address**
  - Địa chỉ liên hệ
  - String (VarChar 500, Optional)
- **avatar**
  - Đường dẫn ảnh đại diện
  - String (VarChar 500, Optional)
- **status**
  - Trạng thái: active | inactive | banned
  - String (VarChar 20, Default: "active")
- **password_hash**
  - Mật khẩu đã được hash (Bcrypt)
  - String (VarChar 255)
- **token_version**
  - Phiên bản token (Tăng lên để logout toàn bộ thiết bị)
  - Int (Default: 0)
- **two_factor_enabled**
  - Trạng thái bảo mật 2 lớp
  - Boolean (Default: false)
- **last_password_change**
  - Thời điểm thay đổi mật khẩu gần nhất
  - DateTime (Optional)
- **last_login_at**
  - Thời điểm đăng nhập cuối cùng
  - DateTime (Optional)
- **created_at**
  - Thời điểm tạo tài khoản
  - DateTime (Default: now)
- **updated_at**
  - Thời điểm cập nhật cuối cùng
  - DateTime (Auto-update)
- **deleted_at**
  - Thời điểm xoá mềm (Soft delete)
  - DateTime (Optional)


## Roles : Định nghĩa các vai trò (ADMIN, USER,...)

- **id**
  - Định danh vai trò
  - UUID
- **role_name**
  - Tên kỹ thuật của role (Unique, ví dụ: admin, editor)
  - String (VarChar 100)
- **display_name**
  - Tên hiển thị đa ngôn ngữ (Ví dụ: {"vi": "Quản trị viên", "en": "Admin"})
  - Json
- **description**
  - Mô tả vai trò đa ngôn ngữ
  - Json (Optional)
- **is_default**
  - Role mặc định khi đăng ký tài khoản mới
  - Boolean (Default: false)
- **is_system**
  - Role hệ thống (Không được xoá)
  - Boolean (Default: false)
- **priority**
  - Độ ưu tiên của role (Số càng lớn ưu tiên càng cao)
  - Int (Default: 0)
- **created_at**
  - Thời điểm tạo bản ghi
  - DateTime
- **updated_at**
  - Thời điểm cập nhật cuối cùng
  - DateTime
- **deleted_at**
  - Thời điểm xoá mềm
  - DateTime (Optional)


## Permissions : Định nghĩa quyền hạn cụ thể (Action:Resource)

- **id**
  - Định danh quyền
  - UUID
- **permission_key**
  - Key kỹ thuật (Unique, ví dụ: create:user, update:order)
  - String (VarChar 250)
- **name**
  - Tên hiển thị đa ngôn ngữ
  - Json
- **description**
  - Mô tả chi tiết đa ngôn ngữ
  - Json (Optional)
- **module_group**
  - Nhóm quyền (Ví dụ: "User Management", "Report") để hiển thị trên UI
  - String (VarChar 100)
- **service_code**
  - Mã microservice sử dụng quyền này (Ví dụ: auth-service)
  - String (VarChar 100)
- **action**
  - Hành động thực hiện (READ, WRITE, DELETE, UPDATE, ALL)
  - String (VarChar 100)
- **scope**
  - Phạm vi quyền (ALL, OWN, GROUP, DEPARTMENT)
  - String (VarChar 50)
- **parent_permission_id**
  - ID quyền cha
  - UUID (Optional)
- **is_active**
  - Trạng thái quyền
  - Boolean (Default: true)
- **created_at**
  - Thời điểm tạo bản ghi
  - DateTime

## BlacklistedToken : Chặn các token đã logout

- **id**, **token**, **expires_at**, **created_at**


## RolePermission : Ánh xạ quyền cho từng vai trò tên table là role_permissions

- **role_id**
  - ID vai trò
  - UUID (Foreign Key)
- **permission_id**
  - ID quyền
  - UUID (Foreign Key)