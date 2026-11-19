import { createHashRouter } from "react-router";
import App from "@/App";
import ArticleList from "@/components/ArticleList/ArticleList";
import LoginPage from "@/pages/LoginPage";
import ErrorPage from "@/pages/ErrorPage";
import ProtectedRoute from "@/components/ProtectedRoute";
const loadBoard = async () => ({ Component: (await import("@/components/Board/BoardPage.jsx")).default });

export const router = createHashRouter(
  [
    {
      path: "/login",
      element: <LoginPage />,
      errorElement: <ErrorPage />,
    },
    {
      path: "/",
      element: (
        <ProtectedRoute>
          <App />
        </ProtectedRoute>
      ),
      errorElement: <ErrorPage />,
      children: [
        {
          path: "/",
          element: <ArticleList />,
        },
        {
          path: "feed/:feedId",
          element: <ArticleList />,
        },
        {
          path: "feed/:feedId/article/:articleId",
          element: <ArticleList />,
        },
        {
          path: "category/:categoryId",
          element: <ArticleList />,
        },
        {
          path: "category/:categoryId/article/:articleId",
          element: <ArticleList />,
        },
        {
          path: "article/:articleId",
          element: <ArticleList />,
        },
        {
          path: "board",
          lazy: loadBoard,
        },
        {
          path: "board/article/:articleId",
          lazy: loadBoard,
        },
      ],
    },
  ],
);
