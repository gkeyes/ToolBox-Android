import { useNavigate } from "react-router-dom";

// Existing controls.spec.js exercises the real toolbar. Here its native/account
// graph is replaced by a constant-height toolbar to isolate page motion.
export default function Toolbar() {
  const navigate = useNavigate();
  return <div className="action-buttons sticky top-0 z-50 bg-background" style={{ height: 56 }}>
    <button aria-label="关闭文章" style={{ minWidth: 48, minHeight: 48 }} onClick={() => navigate("/")}>返回</button>
  </div>;
}
