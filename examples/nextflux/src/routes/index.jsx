import { createHashRouter } from "react-router";
import App from "@/App";
import ArticleList from "@/components/ArticleList/ArticleList";
import LoginPage from "@/pages/LoginPage";
import ErrorPage from "@/pages/ErrorPage";
import SplashScreen from "@/components/SplashScreen";
import ProtectedRoute from "@/components/ProtectedRoute";
import BoardPage from "@/components/Board/BoardPage.jsx";

export const router = createHashRouter(
  [
    {
      path: "/login",
      element: <><LoginPage /><SplashScreen /></>,
      errorElement: <><ErrorPage /><SplashScreen /></>,
    },
    {
      path: "/",
      element: (
        <ProtectedRoute>
          <App />
          <SplashScreen />
        </ProtectedRoute>
      ),
      errorElement: <><ErrorPage /><SplashScreen /></>,
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
          element: <BoardPage />,
        },
        {
          path: "board/article/:articleId",
          element: <BoardPage />,
        },
      ],
    },
  ],
);
